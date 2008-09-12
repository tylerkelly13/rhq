/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.cluster;

import java.util.List;
import java.util.Map;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.authz.Permission;
import org.rhq.core.domain.cluster.FailoverList;
import org.rhq.core.domain.cluster.PartitionEvent;
import org.rhq.core.domain.cluster.PartitionEventDetails;
import org.rhq.core.domain.cluster.PartitionEventType;
import org.rhq.core.domain.cluster.composite.FailoverListComposite;
import org.rhq.core.domain.resource.Agent;
import org.rhq.core.domain.util.PageControl;
import org.rhq.core.domain.util.PageList;
import org.rhq.core.domain.util.PageOrdering;
import org.rhq.core.domain.util.PersistenceUtility;
import org.rhq.enterprise.server.RHQConstants;
import org.rhq.enterprise.server.authz.RequiredPermission;
import org.rhq.enterprise.server.core.AgentManagerLocal;

/**
 * This session beans acts as the underlying implementation the distribution algorithm will
 * interact.  The distribution algorithm runs as a result of various changes in the system 
 * including but not limited to: newly registering agents, currently connecting agents, cloud 
 * membership changes (server added/removed), and redistributions according to agent load. Each 
 * of these changes is captured as a {@link PartitionEvent}, and the distribution will either 
 * need to generated a single (or a set of) {@link FailoverList} objects that are sent down to 
 * the connected agents.  The agents then use these lists to determine which server to fail over 
 * to, if their primary server is unreachable and/or goes down.
 * 
 * @author Joseph Marques
 * @author Jay Shaughnessy
 */
@Stateless
public class PartitionEventManagerBean implements PartitionEventManagerLocal {
    private final Log LOG = LogFactory.getLog(PartitionEventManagerBean.class);

    @PersistenceContext(unitName = RHQConstants.PERSISTENCE_UNIT_NAME)
    private EntityManager entityManager;

    @EJB
    AgentManagerLocal agentManager;

    @EJB
    PartitionEventManagerLocal partitionEventManager;

    @EJB
    FailoverListManagerLocal failoverListManager;

    public FailoverListComposite agentPartitionEvent(Subject subject, String agentName, PartitionEventType eventType) {
        if (eventType.isCloudPartitionEvent() || (null == agentName)) {
            throw new IllegalArgumentException("Invalid agent partition event or no agent specified for event type: "
                + eventType);
        }

        Agent agent = agentManager.getAgentByName(agentName);

        if (null == agent) {
            throw new IllegalArgumentException("Can not perform partition event, agent not found with name: "
                + agentName);
        }

        PartitionEvent partitionEvent = new PartitionEvent(subject.getName(), eventType,
            PartitionEvent.ExecutionStatus.IMMEDIATE);
        entityManager.persist(partitionEvent);

        return failoverListManager.getForSingleAgent(partitionEvent, agent.getName());
    }

    public Map<Agent, FailoverListComposite> cloudPartitionEvent(Subject subject, PartitionEventType eventType) {
        if (!eventType.isCloudPartitionEvent()) {
            throw new IllegalArgumentException("Invalid cloud partition event type: " + eventType);
        }

        PartitionEvent partitionEvent = new PartitionEvent(subject.getName(), eventType,
            PartitionEvent.ExecutionStatus.IMMEDIATE);
        entityManager.persist(partitionEvent);

        return failoverListManager.refresh(partitionEvent);
    }

    public void cloudPartitionEventRequest(Subject subject, PartitionEventType eventType) {
        if (!eventType.isCloudPartitionEvent()) {
            throw new IllegalArgumentException("Invalid cloud partition event type: " + eventType);
        }

        PartitionEvent partitionEvent = new PartitionEvent(subject.getName(), eventType,
            PartitionEvent.ExecutionStatus.REQUESTED);
        entityManager.persist(partitionEvent);
    }

    public void auditPartitionEvent(Subject subject, PartitionEventType eventType) {

        PartitionEvent partitionEvent = new PartitionEvent(subject.getName(), eventType,
            PartitionEvent.ExecutionStatus.AUDIT);
        entityManager.persist(partitionEvent);
    }

    public void deletePartitionEvent(PartitionEvent event) {
        event = entityManager.find(PartitionEvent.class, event.getId());
        for (PartitionEventDetails next : event.getEventDetails()) {
            entityManager.remove(next);
        }
        entityManager.remove(event);
    }

    public void processRequestedPartitionEvents() {
        boolean completedRequest = false;
        Query query = entityManager.createQuery(PartitionEvent.QUERY_FIND_VIA_EXECUTION_STATUS);
        query.setParameter(1, PartitionEvent.ExecutionStatus.REQUESTED);
        @SuppressWarnings("unchecked")
        List<PartitionEvent> requestedPartitionEvents = query.getResultList();

        for (PartitionEvent next : requestedPartitionEvents) {

            // in the rare case of multiple requested partitioning events, just perform one and set
            // the rest completed. There is no sense in repartitioning multiple times on the same data.
            if (!completedRequest) {
                if (!next.getEventType().isCloudPartitionEvent()) {
                    LOG.warn("Invalid cloud partition event type: " + next.getEventType());
                }

                try {
                    failoverListManager.refresh(next);
                } catch (Exception e) {
                    LOG.warn("Failed requested partition event. Setting COMPLETED to avoid repeated failure: " + e);
                }
            }

            next.setExecutionStatus(PartitionEvent.ExecutionStatus.COMPLETED);
        }

        // Notify agents of new server lists
    }

    @RequiredPermission(Permission.MANAGE_INVENTORY)
    public PartitionEvent getPartitionEvent(Subject subject, int partitionEventId) {
        PartitionEvent event = entityManager.find(PartitionEvent.class, partitionEventId);
        return event;
    }

    @RequiredPermission(Permission.MANAGE_INVENTORY)
    public PageList<PartitionEvent> getPartitionEvents(Subject subject, PageControl pageControl) {
        pageControl.initDefaultOrderingField("pe.ctime", PageOrdering.DESC);

        Query query = PersistenceUtility.createQueryWithOrderBy(entityManager, PartitionEvent.QUERY_FIND_ALL,
            pageControl);
        Query countQuery = PersistenceUtility.createCountQuery(entityManager, PartitionEvent.QUERY_FIND_ALL);

        @SuppressWarnings("unchecked")
        List<PartitionEvent> results = query.getResultList();
        long count = (Long) countQuery.getSingleResult();

        return new PageList<PartitionEvent>(results, (int) count, pageControl);
    }

    @RequiredPermission(Permission.MANAGE_INVENTORY)
    public PageList<PartitionEventDetails> getPartitionEventDetails(Subject subject, int partitionEventId,
        PageControl pageControl) {
        pageControl.initDefaultOrderingField("ped.id", PageOrdering.ASC);

        Query query = PersistenceUtility.createQueryWithOrderBy(entityManager,
            PartitionEventDetails.QUERY_FIND_BY_EVENT_ID, pageControl);
        Query countQuery = PersistenceUtility.createCountQuery(entityManager,
            PartitionEventDetails.QUERY_COUNT_BY_EVENT_ID);

        query.setParameter("eventId", partitionEventId);
        countQuery.setParameter("eventId", partitionEventId);

        List<PartitionEventDetails> detailsList = query.getResultList();
        long count = (Long) countQuery.getSingleResult();

        return new PageList<PartitionEventDetails>(detailsList, (int) count, pageControl);
    }
}
