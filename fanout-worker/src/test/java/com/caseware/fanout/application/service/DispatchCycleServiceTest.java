package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.dto.DispatchResult;
import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.fake.FakeCapacitySettings;
import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeLoadEngagement;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.application.fake.ManualExecutor;
import com.caseware.fanout.application.fake.RecordingEventPublisher;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.event.WorkItemCompleted;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DispatchCycleServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final RetryPolicy RETRY = new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30));

    private final FakeClock clock = new FakeClock(T0);
    private final FakeLoadEngagement ems = new FakeLoadEngagement();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final ManualExecutor executor = new ManualExecutor();

    private DispatchCycleService service(WorkItemRepositoryPort repository, FakeCapacitySettings settings) {
        return new DispatchCycleService(repository, ems, settings, clock, events, executor, new FairnessPolicy(),
                LEASE, RETRY);
    }

    private static WorkItem add(WorkItemRepositoryPort repository, String engagement, String firm, Instant addedAt) {
        WorkItem item = WorkItem.submit(new EngagementId(engagement), new FirmId(firm), Reason.SWEEP, addedAt);
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private static long count(WorkItemRepositoryPort repository, WorkItemState state) {
        return repository.status(T0).countsByState().getOrDefault(state, 0L);
    }

    private static void markDone(WorkItemRepositoryPort repository, WorkItemId id, Instant now) {
        WorkItem item = repository.findById(id).orElseThrow();
        long version = item.version();
        item.markDone(now);
        assertThat(repository.save(item, version)).isTrue();
    }

    // US1 scenario 1, SC-001
    @Test
    void neverMoreThanTheGlobalCapInFlightAndEveryItemFinishes() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 1_000; i++) {
            add(repository, "eng-" + i, "firm-" + (i % 10), T0.minusSeconds(1_000 - i));
        }
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(10, 10)));

        int cycles = 0;
        while (count(repository, WorkItemState.DONE) < 1_000 && cycles++ < 500) {
            service.runOnce();
            assertThat(repository.countInFlight(clock.now()).total()).isLessThanOrEqualTo(10);
            assertThat(executor.pending()).isLessThanOrEqualTo(10);
            executor.runAll();
        }

        assertThat(count(repository, WorkItemState.DONE)).isEqualTo(1_000);
        assertThat(ems.calls()).hasSize(1_000);
        assertThat(events.events()).hasSize(1_000).allMatch(WorkItemCompleted.class::isInstance);
    }

    // US1 scenario 2, SC-006
    @Test
    void loweringTheCapStopsNewLoadsUntilBelowItAndRaisingItTakesEffectNextCycle() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 30; i++) {
            add(repository, "eng-" + i, "firm-a", T0.minusSeconds(100 - i));
        }
        FakeCapacitySettings settings = new FakeCapacitySettings(new ConcurrencyLimits(10, 10));
        DispatchCycleService service = service(repository, settings);

        assertThat(service.runOnce().loadsStarted()).isEqualTo(10);

        settings.set(new ConcurrencyLimits(4, 4));
        assertThat(service.runOnce().loadsStarted()).isZero();
        assertThat(executor.pending()).isEqualTo(10);

        for (int i = 0; i < 6; i++) {
            executor.runNext();
        }
        assertThat(service.runOnce().loadsStarted()).isZero();

        executor.runNext();
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);
        assertThat(count(repository, WorkItemState.DONE)).isEqualTo(7);

        settings.set(new ConcurrencyLimits(10, 10));
        assertThat(service.runOnce().loadsStarted()).isEqualTo(6);
        assertThat(repository.countInFlight(clock.now()).total()).isEqualTo(10);
    }

    // Clarification 1, US5 scenario 2
    @Test
    void anItemMarkedDoneAfterBeingReadIsSkippedAndNotLoaded() {
        FakeWorkItemRepository fake = new FakeWorkItemRepository();
        WorkItem item = add(fake, "eng-1", "firm-a", T0);
        InterferingRepository repository = new InterferingRepository(fake);
        repository.afterFindOldestDue = found -> markDone(fake, item.id(), T0);
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(10, 10)));

        DispatchResult result = service.runOnce();

        assertThat(result.claimsSkipped()).isEqualTo(1);
        assertThat(result.loadsStarted()).isZero();
        assertThat(executor.pending()).isZero();
        assertThat(ems.calls()).isEmpty();
    }

    // FR-003 with FR-004, FR-005: the load is not cancelled, so its slot stays taken until it answers
    @Test
    void anItemMarkedDoneDuringItsLoadKeepsItsSlotUntilTheLoadAnswers() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem first = add(repository, "eng-1", "firm-a", T0.minusSeconds(2));
        add(repository, "eng-2", "firm-a", T0.minusSeconds(1));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);

        markDone(repository, first.id(), T0);

        assertThat(service.runOnce().loadsStarted()).isZero();
        executor.runNext();
        assertThat(repository.findById(first.id())).map(WorkItem::state).contains(WorkItemState.DONE);
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);
    }

    @Test
    void anItemMarkedDoneDuringItsLoadKeepsItsSlotUntilTheLeaseExpires() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem first = add(repository, "eng-1", "firm-a", T0.minusSeconds(2));
        add(repository, "eng-2", "firm-a", T0.minusSeconds(1));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));
        service.runOnce();
        markDone(repository, first.id(), T0);

        clock.advance(LEASE.minusSeconds(1));
        assertThat(service.runOnce().loadsStarted()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);
    }

    // contracts/inbound-ports.md step 5: reapply at most 3 times, then leave it for the lease
    @Test
    void aSaveConflictOnAnAnswerIsReappliedAtMostThreeTimesThenLeftForTheLease() {
        FakeWorkItemRepository fake = new FakeWorkItemRepository();
        WorkItem item = add(fake, "eng-1", "firm-a", T0);
        InterferingRepository repository = new InterferingRepository(fake);
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));
        service.runOnce();
        assertThat(executor.pending()).isEqualTo(1);
        repository.failSavesFor.add(item.id());

        executor.runNext();

        assertThat(repository.failedSaves).isEqualTo(4);
        assertThat(fake.findById(item.id())).map(WorkItem::state).contains(WorkItemState.IN_FLIGHT);
        assertThat(fake.findById(item.id()).flatMap(WorkItem::leaseUntil)).contains(T0.plus(LEASE));
        assertThat(events.events()).isEmpty();
    }

    @Test
    void theSlotsPerFirmAreExactlyThoseGivenByTheFairnessPolicy() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 10; i++) {
            add(repository, "a-" + i, "firm-a", T0.minusSeconds(100 + i));
            add(repository, "b-" + i, "firm-b", T0.minusSeconds(50 + i));
            add(repository, "c-" + i, "firm-c", T0.minusSeconds(10 + i));
        }
        ConcurrencyLimits limits = new ConcurrencyLimits(7, 4);
        InFlightCounts before = repository.countInFlight(T0);
        List<FirmBacklog> backlog = repository.findFirmsWithDueWork(T0);
        Map<FirmId, Integer> expected = new FairnessPolicy().allocate(limits, before, backlog);
        assertThat(expected).isNotEmpty();

        service(repository, new FakeCapacitySettings(limits)).runOnce();

        assertThat(repository.countInFlight(T0).perFirm()).isEqualTo(expected);
    }

    /** Lets a test act between the service's reads and writes, as another component would. */
    private static final class InterferingRepository implements WorkItemRepositoryPort {

        private final FakeWorkItemRepository delegate;
        private Consumer<List<WorkItem>> afterFindOldestDue = found -> { };
        private final Set<WorkItemId> failSavesFor = new HashSet<>();
        private int failedSaves;

        private InterferingRepository(FakeWorkItemRepository delegate) {
            this.delegate = delegate;
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
            List<WorkItem> found = new ArrayList<>(delegate.findOldestDue(firm, limit, now));
            afterFindOldestDue.accept(found);
            return found;
        }

        @Override
        public List<WorkItem> findExpiredLeases(Instant now, int limit) {
            return delegate.findExpiredLeases(now, limit);
        }

        @Override
        public boolean save(WorkItem item, long expectedVersion) {
            if (failSavesFor.contains(item.id())) {
                failedSaves++;
                return false;
            }
            return delegate.save(item, expectedVersion);
        }

        @Override
        public WorkStatus status(Instant now) {
            return delegate.status(now);
        }
    }
}
