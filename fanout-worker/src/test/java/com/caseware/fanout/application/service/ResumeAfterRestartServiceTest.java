package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.model.WorkItemState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResumeAfterRestartServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private final FakeWorkItemRepository repository = new FakeWorkItemRepository();
    private final FakeClock clock = new FakeClock(T0);
    private final ResumeAfterRestartService service = new ResumeAfterRestartService(repository, clock);

    // System.Logger writes to java.util.logging when no other provider is installed, as in a unit test.
    private final Logger logger = Logger.getLogger(ResumeAfterRestartService.class.getName());
    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseLog() {
        logger.removeHandler(handler);
    }

    /** An item as a previous process may have left it. */
    private WorkItem leftBehind(String engagement, WorkItemState state, Instant leaseUntil) {
        WorkItem item = WorkItem.restore(new WorkItem.Snapshot(WorkItemId.newId(), new EngagementId(engagement),
                new FirmId("firm-a"), Reason.SWEEP, state, new Attempt(state == WorkItemState.OUTSTANDING ? 0 : 1),
                T0.minusSeconds(600), T0.minusSeconds(600), leaseUntil, null, 3L));
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private List<WorkItem> leftBehindByAPreviousProcess() {
        return List.of(
                leftBehind("eng-expired", WorkItemState.IN_FLIGHT, T0.minusSeconds(1)),
                leftBehind("eng-leased", WorkItemState.IN_FLIGHT, T0.plusSeconds(60)),
                leftBehind("eng-done-leased", WorkItemState.DONE, T0.plusSeconds(60)),
                leftBehind("eng-waiting", WorkItemState.OUTSTANDING, null),
                leftBehind("eng-parked", WorkItemState.PARKED, null));
    }

    @Test
    void resumeReturnsTheNumberOfItemsInFlightWhateverTheirLease() {
        leftBehindByAPreviousProcess();

        assertThat(service.resume()).isEqualTo(2);
    }

    @Test
    void resumeLogsTheNumberOfItemsInFlight() {
        leftBehindByAPreviousProcess();

        service.resume();

        assertThat(logged).extracting(new SimpleFormatter()::formatMessage).anySatisfy(message -> assertThat(message)
                .contains("inFlight=2"));
    }

    @Test
    void resumeChangesNoItem() {
        List<WorkItem> before = leftBehindByAPreviousProcess();

        service.resume();

        for (WorkItem item : before) {
            assertThat(repository.findById(item.id()).orElseThrow().snapshot()).isEqualTo(item.snapshot());
        }
    }

    @Test
    void resumeWithNothingInFlightReturnsZero() {
        leftBehind("eng-waiting", WorkItemState.OUTSTANDING, null);

        assertThat(service.resume()).isZero();
    }
}
