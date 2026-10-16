package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.fake.FakeCapacitySettings;
import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeLoadEngagement;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.application.fake.ManualExecutor;
import com.caseware.fanout.application.fake.RecordingEventPublisher;
import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.caseware.fanout.domain.policy.FairnessPolicy;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarkDoneServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final EngagementId ENGAGEMENT = new EngagementId("eng-1");
    private static final FirmId FIRM = new FirmId("firm-a");

    private final FakeWorkItemRepository repository = new FakeWorkItemRepository();
    private final FakeClock clock = new FakeClock(T0);
    private final FakeLoadEngagement ems = new FakeLoadEngagement();
    private final ManualExecutor executor = new ManualExecutor();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final MarkDoneService service = new MarkDoneService(repository, clock, events);
    private final DispatchCycleService cycle = new DispatchCycleService(repository, ems,
            new FakeCapacitySettings(new ConcurrencyLimits(10, 10)), clock, new RecordingEventPublisher(), executor,
            new FairnessPolicy(), LEASE, new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30)));

    private WorkItem add() {
        WorkItem item = WorkItem.submit(ENGAGEMENT, FIRM, Reason.PUBLISH, T0);
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private WorkItem stored(WorkItem item) {
        return repository.findById(item.id()).orElseThrow();
    }

    @Test
    void anOutstandingItemMarkedDoneIsDoneAndNeverLoaded() {
        WorkItem item = add();
        clock.advance(Duration.ofSeconds(1));

        boolean marked = service.markDone(ENGAGEMENT);
        cycle.runOnce();

        assertThat(marked).isTrue();
        assertThat(stored(item).state()).isEqualTo(WorkItemState.DONE);
        assertThat(events.events()).containsExactly(
                new WorkItemCompleted(item.id(), ENGAGEMENT, FIRM, Attempt.none(), T0.plusSeconds(1)));
        assertThat(executor.pending()).isZero();
        assertThat(ems.calls()).isEmpty();
    }

    @Test
    void anItemInFlightMarkedDoneIsDoneAndKeepsItsLease() {
        WorkItem item = add();
        cycle.runOnce();
        clock.advance(Duration.ofSeconds(1));

        boolean marked = service.markDone(ENGAGEMENT);

        assertThat(marked).isTrue();
        assertThat(stored(item).state()).isEqualTo(WorkItemState.DONE);
        assertThat(stored(item).leaseUntil()).contains(T0.plus(LEASE));
        assertThat(events.events()).containsExactly(
                new WorkItemCompleted(item.id(), ENGAGEMENT, FIRM, new Attempt(1), T0.plusSeconds(1)));
    }

    @Test
    void anEngagementNeverAddedIsNotMarked() {
        boolean marked = service.markDone(ENGAGEMENT);

        assertThat(marked).isFalse();
        assertThat(repository.status(T0).countsByState()).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void anEngagementAlreadyDoneIsNotMarkedAgainAndNothingChanges() {
        WorkItem item = add();
        cycle.runOnce();
        executor.runAll();
        WorkItem.Snapshot before = stored(item).snapshot();

        boolean marked = service.markDone(ENGAGEMENT);

        assertThat(marked).isFalse();
        assertThat(stored(item).snapshot()).isEqualTo(before);
        assertThat(events.events()).isEmpty();
    }
}
