package com.caseware.fanout.adapter.in.scheduler;

import com.caseware.fanout.application.port.in.ResumeAfterRestartUseCase;
import com.caseware.fanout.application.port.in.RunDispatchCycleUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs one dispatch cycle at a time, with a fixed delay between cycles so they never overlap. The
 * first tick reports what a previous process left in flight before its cycle.
 */
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final ResumeAfterRestartUseCase resumeAfterRestart;
    private final RunDispatchCycleUseCase dispatchCycle;
    private boolean resumed;

    @Scheduled(fixedDelayString = "${fanout.dispatch.period}")
    public void tick() {
        if (!resumed) {
            resumeAfterRestart.resume();
            resumed = true;
        }
        dispatchCycle.runOnce();
    }
}
