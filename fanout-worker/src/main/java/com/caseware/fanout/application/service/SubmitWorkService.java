package com.caseware.fanout.application.service;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.SubmitWorkCommand;
import com.caseware.fanout.application.port.in.SubmitWorkUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.WorkItemId;

/** Stub for T038; implemented in T041. */
public final class SubmitWorkService implements SubmitWorkUseCase {

    public SubmitWorkService(WorkItemRepositoryPort repository, ClockPort clock) {
    }

    @Override
    public SubmitResult submit(SubmitWorkCommand command) {
        return new SubmitResult(WorkItemId.newId(), false);
    }
}
