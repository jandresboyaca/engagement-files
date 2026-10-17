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
import com.caseware.fanout.domain.event.WorkItemParked;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
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

    private DispatchCycleService service(WorkItemRepositoryPort repository, FakeCapacitySettings settings,
            RetryPolicy retry) {
        return new DispatchCycleService(repository, ems, settings, clock, events, executor, new FairnessPolicy(),
                LEASE, retry);
    }

    /** An item in any state, as a previous process may have left it. */
    private static WorkItem leftBehind(WorkItemRepositoryPort repository, String engagement, WorkItemState state,
            Instant leaseUntil) {
        WorkItem item = WorkItem.restore(new WorkItem.Snapshot(WorkItemId.newId(), new EngagementId(engagement),
                new FirmId("firm-a"), Reason.SWEEP, state, new Attempt(state == WorkItemState.OUTSTANDING ? 0 : 1),
                T0.minusSeconds(600), T0.minusSeconds(600), leaseUntil, null, 0L));
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private static WorkItem stored(WorkItemRepositoryPort repository, WorkItem item) {
        return repository.findById(item.id()).orElseThrow();
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

    // US2 scenario 2: when a slot frees up, the firm with nothing in flight gets it before the older firm
    @Test
    void aFreedSlotGoesToTheNewlyAddedFirmBeforeTheFirmHoldingItsShare() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 6; i++) {
            add(repository, "a-" + i, "firm-a", T0.minusSeconds(100 - i));
        }
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(4, 4)));
        assertThat(service.runOnce().loadsStarted()).isEqualTo(4);
        for (int i = 0; i < 3; i++) {
            add(repository, "b-" + i, "firm-b", T0.minusSeconds(10 - i));
        }

        executor.runNext();
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);

        assertThat(repository.countInFlight(clock.now()).perFirm())
                .isEqualTo(Map.of(new FirmId("firm-a"), 3, new FirmId("firm-b"), 1));
    }

    // US2 scenarios 1 and 3, SC-002, and SC-001 at 40,000 items
    @Test
    void smallFirmsAddedAfterALargeOneFinishWithinNineRoundsWithinCAndF() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 40_000; i++) {
            add(repository, "a-" + i, "firm-a", T0.minusSeconds(100_000 - i));
        }
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(20, 5)));

        service.runOnce();
        assertThat(repository.countInFlight(clock.now()).perFirm())
                .as("a single firm is still capped at F").isEqualTo(Map.of(new FirmId("firm-a"), 5));
        executor.runAll();

        List<FirmId> smallFirms = List.of(new FirmId("firm-b"), new FirmId("firm-c"), new FirmId("firm-d"));
        for (FirmId firm : smallFirms) {
            for (int i = 0; i < 40; i++) {
                add(repository, firm.value() + "-" + i, firm.value(), T0.minusSeconds(40 - i));
            }
        }

        Map<FirmId, Integer> roundDone = new java.util.HashMap<>();
        for (int round = 1; round <= 12 && roundDone.size() < smallFirms.size(); round++) {
            service.runOnce();
            InFlightCounts inFlight = repository.countInFlight(clock.now());
            assertThat(inFlight.total()).as("loads in flight after round %d", round).isLessThanOrEqualTo(20);
            assertThat(inFlight.perFirm().values()).as("per-firm loads after round %d", round)
                    .allMatch(loads -> loads <= 5);
            executor.runAll();
            Map<FirmId, Map<WorkItemState, Long>> byFirm = repository.status(clock.now()).countsByFirm();
            for (FirmId firm : smallFirms) {
                long done = byFirm.getOrDefault(firm, Map.of()).getOrDefault(WorkItemState.DONE, 0L);
                if (done == 40) {
                    roundDone.putIfAbsent(firm, round);
                }
            }
        }

        assertThat(roundDone).containsOnlyKeys(smallFirms);
        assertThat(roundDone.values()).allMatch(round -> round <= 9);
    }

    // FR-006 with more firms than C (review C1): the small firm's wait depends on how many firms have
    // work, not on the size of their backlogs
    @Test
    void aSmallFirmAddedBehindManyOlderFirmsGetsItsFirstSlotAndFinishesWithinItsFairShare() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        int olderFirms = 100;
        for (int f = 0; f < olderFirms; f++) {
            for (int i = 0; i < 100; i++) {
                add(repository, "old-" + f + "-" + i, "old-" + f, T0.minusSeconds(100_000 - f * 100L - i));
            }
        }
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(20, 5)));
        service.runOnce();
        executor.runAll();

        FirmId small = new FirmId("firm-small");
        for (int i = 0; i < 20; i++) {
            add(repository, "small-" + i, small.value(), T0.minusSeconds(20 - i));
        }
        int perSlot = (olderFirms + 1 + 19) / 20; // ceil(N / C) rounds
        int bound = 20 * perSlot; // n items x ceil(N / C) rounds

        int firstSlot = 0;
        int roundDone = 0;
        for (int round = 1; round <= bound + 5 && roundDone == 0; round++) {
            service.runOnce();
            if (firstSlot == 0 && repository.countInFlight(clock.now()).perFirm().containsKey(small)) {
                firstSlot = round;
            }
            executor.runAll();
            long done = repository.status(clock.now()).countsByFirm().getOrDefault(small, Map.of())
                    .getOrDefault(WorkItemState.DONE, 0L);
            if (done == 20) {
                roundDone = round;
            }
        }

        assertThat(firstSlot).as("round of the small firm's first slot").isBetween(1, perSlot);
        assertThat(roundDone).as("round the small firm finished").isBetween(1, bound);
    }

    // US3 scenario 1, FR-011: the delay grows per attempt and other items keep being loaded meanwhile
    @Test
    void aRetryableFailureWaitsForItsBackoffWhileOtherItemsKeepLoading() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem bad = add(repository, "bad", "firm-a", T0.minusSeconds(10));
        WorkItem ok1 = add(repository, "ok-1", "firm-a", T0.minusSeconds(9));
        WorkItem ok2 = add(repository, "ok-2", "firm-a", T0.minusSeconds(8));
        ems.script(bad.engagementId(), new LoadOutcome.Answered(503));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));

        service.runOnce();
        executor.runAll();
        assertThat(stored(repository, bad).state()).isEqualTo(WorkItemState.OUTSTANDING);
        assertThat(stored(repository, bad).nextAttemptAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(stored(repository, bad).lastError()).contains("HTTP_503");

        service.runOnce();
        executor.runAll();
        service.runOnce();
        executor.runAll();
        assertThat(stored(repository, ok1).state()).isEqualTo(WorkItemState.DONE);
        assertThat(stored(repository, ok2).state()).isEqualTo(WorkItemState.DONE);

        clock.advance(Duration.ofSeconds(30));
        service.runOnce();
        executor.runAll();
        assertThat(stored(repository, bad).attempt()).isEqualTo(new Attempt(2));
        assertThat(stored(repository, bad).nextAttemptAt()).isEqualTo(T0.plusSeconds(30 + 60));
        assertThat(ems.calls()).extracting(EngagementId::value).containsExactly("bad", "ok-1", "ok-2", "bad");
    }

    // US3 scenario 1, FR-009: no answer before the lease ends is a timeout, handled by step 1
    @Test
    void aLoadWithNoAnswerBeforeItsLeaseEndsIsATimeoutAndIsRetried() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem item = add(repository, "eng-1", "firm-a", T0);
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));
        service.runOnce();

        clock.advance(LEASE);
        DispatchResult expiry = service.runOnce();

        assertThat(expiry.leasesExpired()).isEqualTo(1);
        assertThat(expiry.loadsStarted()).isZero();
        assertThat(stored(repository, item).state()).isEqualTo(WorkItemState.OUTSTANDING);
        assertThat(stored(repository, item).lastError()).contains("TIMEOUT");
        assertThat(stored(repository, item).nextAttemptAt()).isEqualTo(T0.plus(LEASE).plusSeconds(30));

        clock.advance(Duration.ofSeconds(30));
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);
        executor.runNext();
        assertThat(stored(repository, item).state()).as("the late answer to attempt 1 is ignored")
                .isEqualTo(WorkItemState.IN_FLIGHT);
        assertThat(stored(repository, item).attempt()).isEqualTo(new Attempt(2));
    }

    // US3 scenario 2: parked after the maximum, stops taking a slot, visible with its last error
    @Test
    void afterTheMaximumAttemptsAnItemIsParkedAndStopsTakingASlot() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem bad = add(repository, "bad", "firm-a", T0);
        ems.script(bad.engagementId(), new LoadOutcome.Answered(503));
        RetryPolicy threeAttempts = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(30));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)),
                threeAttempts);

        for (int round = 0; round < 5; round++) {
            service.runOnce();
            executor.runAll();
            clock.advance(Duration.ofMinutes(30));
        }

        assertThat(ems.callsFor(bad.engagementId())).isEqualTo(3);
        assertThat(stored(repository, bad).state()).isEqualTo(WorkItemState.PARKED);
        assertThat(stored(repository, bad).lastError()).contains("HTTP_503");
        assertThat(events.events()).filteredOn(WorkItemParked.class::isInstance).hasSize(1);
        assertThat(repository.status(clock.now()).countsByState()).containsEntry(WorkItemState.PARKED, 1L);

        WorkItem next = add(repository, "next", "firm-a", clock.now());
        assertThat(service.runOnce().loadsStarted()).isEqualTo(1);
        assertThat(stored(repository, next).state()).isEqualTo(WorkItemState.IN_FLIGHT);
    }

    // US3 scenario 3
    @Test
    void aPermanentFailureIsParkedWithoutRetry() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem missing = add(repository, "missing", "firm-a", T0);
        ems.script(missing.engagementId(), new LoadOutcome.Answered(404));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(1, 1)));

        service.runOnce();
        executor.runAll();
        clock.advance(Duration.ofHours(1));
        service.runOnce();
        executor.runAll();

        assertThat(ems.callsFor(missing.engagementId())).isEqualTo(1);
        assertThat(stored(repository, missing).state()).isEqualTo(WorkItemState.PARKED);
        assertThat(stored(repository, missing).lastError()).contains("HTTP_404");
    }

    // FR-009: an overload answer is a retryable failure
    @Test
    void overloadAnswersAreRetryable() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem busy = add(repository, "busy", "firm-a", T0.minusSeconds(2));
        WorkItem throttled = add(repository, "throttled", "firm-a", T0.minusSeconds(1));
        ems.script(busy.engagementId(), new LoadOutcome.Answered(503));
        ems.script(throttled.engagementId(), new LoadOutcome.Answered(429));
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(2, 2)));

        service.runOnce();
        executor.runAll();

        assertThat(List.of(stored(repository, busy), stored(repository, throttled)))
                .allMatch(item -> item.state() == WorkItemState.OUTSTANDING)
                .allMatch(item -> item.nextAttemptAt().equals(T0.plusSeconds(30)));
        assertThat(stored(repository, throttled).lastError()).contains("HTTP_429");
    }

    // Spec edge case: a firm whose items are all parked does not affect other firms
    @Test
    void aFirmWhoseItemsAreAllParkedDoesNotAffectOtherFirms() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        for (int i = 0; i < 3; i++) {
            WorkItem missing = add(repository, "x-" + i, "firm-x", T0.minusSeconds(10 - i));
            ems.script(missing.engagementId(), new LoadOutcome.Answered(404));
            add(repository, "y-" + i, "firm-y", T0.minusSeconds(5 - i));
        }
        DispatchCycleService service = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(2, 2)));

        for (int round = 0; round < 6; round++) {
            service.runOnce();
            executor.runAll();
        }

        Map<FirmId, Map<WorkItemState, Long>> byFirm = repository.status(clock.now()).countsByFirm();
        assertThat(byFirm.get(new FirmId("firm-x"))).isEqualTo(Map.of(WorkItemState.PARKED, 3L));
        assertThat(byFirm.get(new FirmId("firm-y"))).isEqualTo(Map.of(WorkItemState.DONE, 3L));
    }

    // FR-015, US4 scenario 2: a new service over the same work list stands for a restarted process
    @Test
    void afterARestartAnItemLeftInFlightIsLoadedOnceMoreAfterItsLeaseAndUnexpiredLeasesKeepTheirSlots() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem expired = leftBehind(repository, "expired", WorkItemState.IN_FLIGHT, T0.minusSeconds(1));
        WorkItem live = leftBehind(repository, "live", WorkItemState.IN_FLIGHT, T0.plusSeconds(60));
        WorkItem doneLive = leftBehind(repository, "done-live", WorkItemState.DONE, T0.plusSeconds(60));
        WorkItem waiting = leftBehind(repository, "waiting", WorkItemState.OUTSTANDING, null);
        DispatchCycleService restarted = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(2, 2)));

        DispatchResult first = restarted.runOnce();
        assertThat(first.leasesExpired()).isEqualTo(1);
        assertThat(first.loadsStarted()).as("both unexpired leases still hold a slot").isZero();

        clock.set(T0.plusSeconds(60));
        DispatchResult second = restarted.runOnce();
        assertThat(second.leasesExpired()).isEqualTo(1);
        assertThat(second.loadsStarted()).isEqualTo(2);
        executor.runAll();
        clock.set(T0.plusSeconds(90));
        restarted.runOnce();
        executor.runAll();

        assertThat(ems.callsFor(expired.engagementId())).isEqualTo(1);
        assertThat(ems.callsFor(live.engagementId())).isEqualTo(1);
        assertThat(ems.callsFor(waiting.engagementId())).isEqualTo(1);
        assertThat(ems.callsFor(doneLive.engagementId())).isZero();
        assertThat(List.of(expired, live, waiting)).allMatch(item -> stored(repository, item).state() == WorkItemState.DONE);
    }

    // Regression check (tasks.md T030), expected to pass on its first run: US4 scenario 1
    @Test
    void regressionCheck_afterARestartDoneItemsAreNeverLoadedAgain() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        WorkItem done = leftBehind(repository, "done", WorkItemState.DONE, null);
        DispatchCycleService restarted = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(2, 2)));

        for (int round = 0; round < 3; round++) {
            restarted.runOnce();
            executor.runAll();
            clock.advance(Duration.ofMinutes(10));
        }

        assertThat(ems.callsFor(done.engagementId())).isZero();
    }

    // Regression check (tasks.md T030), expected to pass on its first run: US4 scenario 3
    @Test
    void regressionCheck_afterARestartOutstandingItemsAreAllLoaded() {
        FakeWorkItemRepository repository = new FakeWorkItemRepository();
        List<WorkItem> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(leftBehind(repository, "waiting-" + i, WorkItemState.OUTSTANDING, null));
        }
        DispatchCycleService restarted = service(repository, new FakeCapacitySettings(new ConcurrencyLimits(2, 2)));

        for (int round = 0; round < 3; round++) {
            restarted.runOnce();
            executor.runAll();
        }

        assertThat(waiting).allMatch(item -> stored(repository, item).state() == WorkItemState.DONE);
        assertThat(ems.calls()).hasSize(5);
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
