package com.caseware.fanout.adapter.in.scheduler;

import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.port.in.GetWorkStatusUseCase;
import com.caseware.fanout.application.port.in.ResumeAfterRestartUseCase;
import com.caseware.fanout.application.port.in.RunDispatchCycleUseCase;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItemState;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs one dispatch cycle at a time, with a fixed delay between cycles so they never overlap. The
 * first tick reports what a previous process left in flight before its cycle. After every 60th
 * cycle it logs the work status, which is how the counts of FR-016 are exposed (opaque ids only).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private static final int CYCLES_PER_STATUS = 60;

    private final ResumeAfterRestartUseCase resumeAfterRestart;
    private final RunDispatchCycleUseCase dispatchCycle;
    private final GetWorkStatusUseCase workStatus;
    private boolean resumed;
    private long cycles;

    @Scheduled(fixedDelayString = "${fanout.dispatch.period}")
    public void tick() {
        if (!resumed) {
            resumeAfterRestart.resume();
            resumed = true;
        }
        dispatchCycle.runOnce();
        if (++cycles % CYCLES_PER_STATUS == 0) {
            logStatus(workStatus.status());
        }
    }

    private static void logStatus(WorkStatus status) {
        String perFirm = status.countsByFirm().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(FirmId::value)))
                .map(firm -> firm.getKey().value() + " " + counts(firm.getValue()))
                .collect(Collectors.joining("; ", "[", "]"));
        log.info("status {} loadsInFlight={} oldestOutstandingAge={} perFirm={}", counts(status.countsByState()),
                status.loadsInFlight(), status.oldestOutstandingAge().map(Duration::toString).orElse("none"), perFirm);
    }

    private static String counts(Map<WorkItemState, Long> byState) {
        return "outstanding=" + byState.getOrDefault(WorkItemState.OUTSTANDING, 0L)
                + " inFlight=" + byState.getOrDefault(WorkItemState.IN_FLIGHT, 0L)
                + " done=" + byState.getOrDefault(WorkItemState.DONE, 0L)
                + " parked=" + byState.getOrDefault(WorkItemState.PARKED, 0L);
    }
}
