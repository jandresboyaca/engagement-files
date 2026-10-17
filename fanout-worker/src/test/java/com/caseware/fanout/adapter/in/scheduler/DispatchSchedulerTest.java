package com.caseware.fanout.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.caseware.fanout.application.dto.DispatchResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItemState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DispatchSchedulerTest {

    private static final WorkStatus STATUS = new WorkStatus(
            Map.of(WorkItemState.OUTSTANDING, 3L, WorkItemState.IN_FLIGHT, 2L, WorkItemState.DONE, 7L,
                    WorkItemState.PARKED, 1L),
            Map.of(new FirmId("firm-b"), Map.of(WorkItemState.DONE, 7L),
                    new FirmId("firm-a"), Map.of(WorkItemState.OUTSTANDING, 3L, WorkItemState.IN_FLIGHT, 2L,
                            WorkItemState.PARKED, 1L)),
            2, Optional.of(Duration.ofMinutes(10)));

    private final List<String> calls = new ArrayList<>();
    private WorkStatus status = STATUS;
    private final DispatchScheduler scheduler = new DispatchScheduler(
            () -> {
                calls.add("resume");
                return 0;
            },
            () -> {
                calls.add("runOnce");
                return new DispatchResult(0, 0, 0);
            },
            () -> {
                calls.add("status");
                return status;
            });

    private final Logger logger = (Logger) LoggerFactory.getLogger(DispatchScheduler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            scheduler.tick();
        }
    }

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

    // FR-016: the status log line is how the counts are exposed (review I4)
    @Test
    void theStatusIsReadAndLoggedAfterEvery60thCycleOnly() {
        tick(59);
        assertThat(calls).doesNotContain("status");
        assertThat(appender.list).isEmpty();

        tick(1);
        assertThat(calls.subList(calls.size() - 2, calls.size())).containsExactly("runOnce", "status");
        assertThat(appender.list).hasSize(1);

        tick(60);
        assertThat(calls).filteredOn("status"::equals).hasSize(2);
        assertThat(appender.list).hasSize(2);
    }

    @Test
    void theStatusLineHasCountsPerStateAndPerFirmLoadsInFlightAndTheOldestAgeWithOpaqueIdsOnly() {
        tick(60);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "status outstanding=3 inFlight=2 done=7 parked=1 loadsInFlight=2 oldestOutstandingAge=PT10M"
                        + " perFirm=[firm-a outstanding=3 inFlight=2 done=0 parked=1;"
                        + " firm-b outstanding=0 inFlight=0 done=7 parked=0]");
    }

    @Test
    void anEmptyWorkListLogsZerosAndNoAge() {
        status = new WorkStatus(Map.of(), Map.of(), 0, Optional.empty());

        tick(60);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "status outstanding=0 inFlight=0 done=0 parked=0 loadsInFlight=0 oldestOutstandingAge=none"
                        + " perFirm=[]");
    }
}
