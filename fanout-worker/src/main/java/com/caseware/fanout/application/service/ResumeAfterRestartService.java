package com.caseware.fanout.application.service;

import com.caseware.fanout.application.port.in.ResumeAfterRestartUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;

/** Stub for T035; implemented in T037. */
public final class ResumeAfterRestartService implements ResumeAfterRestartUseCase {

    public ResumeAfterRestartService(WorkItemRepositoryPort repository, ClockPort clock) {
    }

    @Override
    public int resume() {
        return 0;
    }
}
