package com.caseware.fanout.application.service;

import com.caseware.fanout.application.port.in.ResumeAfterRestartUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.WorkItemState;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Reports what a previous process left in flight (contracts/inbound-ports.md). Changes nothing:
 * those items keep their slot until their lease ends, then cycle step 1 expires them (research §5).
 */
public final class ResumeAfterRestartService implements ResumeAfterRestartUseCase {

    private static final Logger LOG = System.getLogger(ResumeAfterRestartService.class.getName());

    private final WorkItemRepositoryPort repository;
    private final ClockPort clock;

    public ResumeAfterRestartService(WorkItemRepositoryPort repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public int resume() {
        long inFlight = repository.status(clock.now()).countsByState().getOrDefault(WorkItemState.IN_FLIGHT, 0L);
        LOG.log(Level.INFO, "resume inFlight={0}: items left in flight keep their slot until their lease ends",
                inFlight);
        return (int) inFlight;
    }
}
