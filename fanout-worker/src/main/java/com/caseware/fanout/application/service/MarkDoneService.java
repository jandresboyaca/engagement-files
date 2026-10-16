package com.caseware.fanout.application.service;

import com.caseware.fanout.application.port.in.MarkDoneUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.event.DomainEvent;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.WorkItem;
import java.util.List;
import java.util.Optional;

/**
 * Closes the open item for an engagement (contracts/inbound-ports.md). A load in flight is not
 * cancelled: {@link WorkItem#markDone} keeps its lease, so its slot stays counted until it answers.
 */
public final class MarkDoneService implements MarkDoneUseCase {

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
        Optional<WorkItem> open = repository.findOpenByEngagement(engagementId);
        if (open.isEmpty()) {
            return false;
        }
        WorkItem item = open.get();
        long readVersion = item.version();
        item.markDone(clock.now());
        List<DomainEvent> raised = item.pullEvents();
        if (!repository.save(item, readVersion)) {
            return false;
        }
        raised.forEach(events::publish);
        return true;
    }
}
