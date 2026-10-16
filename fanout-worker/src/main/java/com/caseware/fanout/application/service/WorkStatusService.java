package com.caseware.fanout.application.service;

import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.port.in.GetWorkStatusUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import java.util.Map;
import java.util.Optional;

/** Stub for T042; implemented in T043. */
public final class WorkStatusService implements GetWorkStatusUseCase {

    public WorkStatusService(WorkItemRepositoryPort repository, ClockPort clock) {
    }

    @Override
    public WorkStatus status() {
        return new WorkStatus(Map.of(), Map.of(), 0, Optional.empty());
    }
}
