package com.caseware.fanout.application.service;

import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.port.in.GetWorkStatusUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;

/** Counts for operators (contracts/inbound-ports.md); the repository computes them in one read. */
public final class WorkStatusService implements GetWorkStatusUseCase {

    private final WorkItemRepositoryPort repository;
    private final ClockPort clock;

    public WorkStatusService(WorkItemRepositoryPort repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public WorkStatus status() {
        return repository.status(clock.now());
    }
}
