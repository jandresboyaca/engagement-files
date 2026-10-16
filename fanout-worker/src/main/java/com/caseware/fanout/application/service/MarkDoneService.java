package com.caseware.fanout.application.service;

import com.caseware.fanout.application.port.in.MarkDoneUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.event.DomainEvent;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.WorkItem;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;

/**
 * Closes the open item for an engagement (contracts/inbound-ports.md). A load in flight is not
 * cancelled: {@link WorkItem#markDone} keeps its lease, so its slot stays counted until it answers.
 * On a save conflict it reloads the open item and reapplies, at most 3 times, as the cycle does.
 */
public final class MarkDoneService implements MarkDoneUseCase {

    private static final Logger LOG = System.getLogger(MarkDoneService.class.getName());
    private static final int MAX_REAPPLIES = 3;

    private final WorkItemRepositoryPort repository;
    private final ClockPort clock;
    private final DomainEventPublisherPort events;

    public MarkDoneService(WorkItemRepositoryPort repository, ClockPort clock, DomainEventPublisherPort events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public boolean markDone(EngagementId engagementId) {
        for (int apply = 0; apply <= MAX_REAPPLIES; apply++) {
            Optional<WorkItem> open = repository.findOpenByEngagement(engagementId);
            if (open.isEmpty()) {
                return false;
            }
            WorkItem item = open.get();
            long readVersion = item.version();
            item.markDone(clock.now());
            List<DomainEvent> raised = item.pullEvents();
            if (repository.save(item, readVersion)) {
                raised.forEach(events::publish);
                return true;
            }
        }
        LOG.log(Level.WARNING, "markDone for engagementId={0} not saved after {1} conflicts", engagementId.value(),
                MAX_REAPPLIES + 1);
        return false;
    }
}
