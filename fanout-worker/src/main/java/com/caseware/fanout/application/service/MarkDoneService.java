package com.caseware.fanout.application.service;

import com.caseware.fanout.application.port.in.MarkDoneUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.EngagementId;

/** Stub for T039; implemented in T041. */
public final class MarkDoneService implements MarkDoneUseCase {

    public MarkDoneService(WorkItemRepositoryPort repository, ClockPort clock, DomainEventPublisherPort events) {
    }

    @Override
    public boolean markDone(EngagementId engagementId) {
        return false;
    }
}
