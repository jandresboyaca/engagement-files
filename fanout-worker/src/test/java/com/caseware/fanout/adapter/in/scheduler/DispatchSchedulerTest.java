package com.caseware.fanout.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.dto.DispatchResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchSchedulerTest {

    private final List<String> calls = new ArrayList<>();
    private final DispatchScheduler scheduler = new DispatchScheduler(
            () -> {
                calls.add("resume");
                return 0;
            },
            () -> {
                calls.add("runOnce");
                return new DispatchResult(0, 0, 0);
            });

    @Test
    void theFirstTickResumesAndThenRunsTheCycle() {
        scheduler.tick();

        assertThat(calls).containsExactly("resume", "runOnce");
    }

    @Test
    void laterTicksOnlyRunTheCycle() {
        scheduler.tick();
        calls.clear();

        scheduler.tick();
        scheduler.tick();

        assertThat(calls).containsExactly("runOnce", "runOnce");
    }
}
