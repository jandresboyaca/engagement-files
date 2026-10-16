package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.fake.FakeCapacitySettings;
import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeLoadEngagement;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.application.fake.ManualExecutor;
import com.caseware.fanout.application.fake.RecordingEventPublisher;
import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.caseware.fanout.domain.policy.FairnessPolicy;
import com.caseware.fanout.domain.policy.FirmBacklog;
import com.caseware.fanout.domain.policy.InFlightCounts;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
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

    // Save conflicts

    /**
     * The fake repository, with something run just before each save, as another writer would
     * change the item between markDone's read and its save.
     */
    private record ChangedBeforeSave(WorkItemRepositoryPort delegate, List<Runnable> beforeSaves)
            implements WorkItemRepositoryPort {

        @Override
        public boolean save(WorkItem item, long expectedVersion) {
            if (!beforeSaves.isEmpty()) {
                beforeSaves.removeFirst().run();
            }
            return delegate.save(item, expectedVersion);
        }

        @Override
        public SubmitResult insertIfNoOpenItem(WorkItem item) {
            return delegate.insertIfNoOpenItem(item);
        }

        @Override
        public Optional<WorkItem> findOpenByEngagement(EngagementId engagementId) {
            return delegate.findOpenByEngagement(engagementId);
        }

        @Override
        public Optional<WorkItem> findById(WorkItemId id) {
            return delegate.findById(id);
        }

        @Override
        public InFlightCounts countInFlight(Instant now) {
            return delegate.countInFlight(now);
        }

        @Override
        public List<FirmBacklog> findFirmsWithDueWork(Instant now) {
            return delegate.findFirmsWithDueWork(now);
        }

        @Override
        public List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now) {
            return delegate.findOldestDue(firm, limit, now);
        }

        @Override
        public List<WorkItem> findExpiredLeases(Instant now, int limit) {
            return delegate.findExpiredLeases(now, limit);
        }

        @Override
        public WorkStatus status(Instant now) {
            return delegate.status(now);
        }
    }

    private MarkDoneService serviceWithChangesBeforeSaves(Runnable... changes) {
        return new MarkDoneService(new ChangedBeforeSave(repository, new ArrayList<>(List.of(changes))), clock,
                events);
    }

    /** Another writer that bumps the item's version without changing anything else. */
    private Runnable touch(WorkItem item) {
        return () -> {
            WorkItem current = stored(item);
            repository.save(current, current.version());
        };
    }

    @Test
    void whenTheCycleClaimsTheItemBeforeTheSaveTheItemInFlightIsMarkedDoneOnReapply() {
        WorkItem item = add();
        MarkDoneService racing = serviceWithChangesBeforeSaves(cycle::runOnce);

        boolean marked = racing.markDone(ENGAGEMENT);

        assertThat(marked).isTrue();
        assertThat(stored(item).state()).isEqualTo(WorkItemState.DONE);
        assertThat(stored(item).leaseUntil()).contains(T0.plus(LEASE));
        assertThat(events.events()).containsExactly(
                new WorkItemCompleted(item.id(), ENGAGEMENT, FIRM, new Attempt(1), T0));
    }

    @Test
    void whenTheItemIsClosedBeforeTheSaveTheReloadFindsNoOpenItem() {
        WorkItem item = add();
        MarkDoneService racing = serviceWithChangesBeforeSaves(() -> {
            cycle.runOnce();
            executor.runAll();
        });

        boolean marked = racing.markDone(ENGAGEMENT);

        assertThat(marked).isFalse();
        assertThat(stored(item).state()).isEqualTo(WorkItemState.DONE);
        assertThat(events.events()).isEmpty();
    }

    @Test
    void afterThreeReappliesThatStillConflictMarkDoneGivesUpWithAWarning() {
        WorkItem item = add();
        MarkDoneService racing = serviceWithChangesBeforeSaves(touch(item), touch(item), touch(item), touch(item));
        Logger logger = Logger.getLogger(MarkDoneService.class.getName());
        List<LogRecord> logged = new ArrayList<>();
        Handler handler = new Handler() {
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
        logger.addHandler(handler);
        try {
            boolean marked = racing.markDone(ENGAGEMENT);

            assertThat(marked).isFalse();
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(stored(item).state()).isEqualTo(WorkItemState.OUTSTANDING);
        assertThat(stored(item).version()).isEqualTo(4L);
        assertThat(events.events()).isEmpty();
        assertThat(logged).filteredOn(record -> record.getLevel() == Level.WARNING)
                .extracting(new SimpleFormatter()::formatMessage)
                .containsExactly("markDone for engagementId=eng-1 not saved after 4 conflicts");
    }
}
