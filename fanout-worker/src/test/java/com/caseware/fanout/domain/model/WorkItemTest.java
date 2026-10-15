package com.caseware.fanout.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.exception.InvalidTransitionException;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final RetryPolicy RETRY = new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30));
    private static final LoadOutcome SUCCESS = new LoadOutcome.Answered(204);

    private final EngagementId engagement = new EngagementId("eng-1");
    private final FirmId firm = new FirmId("firm-1");

    private WorkItem submitted() {
        return WorkItem.submit(engagement, firm, Reason.PUBLISH, T0);
    }

    private WorkItem inFlight() {
        WorkItem item = submitted();
        item.claim(T0, LEASE);
        item.pullEvents();
        return item;
    }

    @Test
    void submitCreatesAnOutstandingItemDueNow() {
        WorkItem item = submitted();

        assertThat(item.id()).isNotNull();
        assertThat(item.engagementId()).isEqualTo(engagement);
        assertThat(item.firmId()).isEqualTo(firm);
        assertThat(item.reason()).isEqualTo(Reason.PUBLISH);
        assertThat(item.state()).isEqualTo(WorkItemState.OUTSTANDING);
        assertThat(item.attempt()).isEqualTo(Attempt.none());
        assertThat(item.addedAt()).isEqualTo(T0);
        assertThat(item.nextAttemptAt()).isEqualTo(T0);
        assertThat(item.leaseUntil()).isEmpty();
        assertThat(item.lastError()).isEmpty();
    }

    @Test
    void claimMovesADueItemInFlightWithALease() {
        WorkItem item = submitted();

        item.claim(T0, LEASE);

        assertThat(item.state()).isEqualTo(WorkItemState.IN_FLIGHT);
        assertThat(item.attempt()).isEqualTo(new Attempt(1));
        assertThat(item.leaseUntil()).contains(T0.plus(LEASE));
    }

    @Test
    void claimBeforeTheItemIsDueIsRejected() {
        WorkItem item = submitted();

        assertThatThrownBy(() -> item.claim(T0.minusSeconds(1), LEASE))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void claimOfAnItemAlreadyInFlightIsRejected() {
        WorkItem item = inFlight();

        assertThatThrownBy(() -> item.claim(T0, LEASE)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void successfulAnswerCompletesTheItemAndClearsTheLease() {
        WorkItem item = inFlight();

        item.answer(new Attempt(1), SUCCESS, T0.plusSeconds(60), RETRY);

        assertThat(item.state()).isEqualTo(WorkItemState.DONE);
        assertThat(item.leaseUntil()).isEmpty();
        assertThat(item.pullEvents()).containsExactly(
                new WorkItemCompleted(item.id(), engagement, firm, new Attempt(1), T0.plusSeconds(60)));
    }

    @Test
    void markDoneOnAnOutstandingItemCompletesIt() {
        WorkItem item = submitted();

        item.markDone(T0.plusSeconds(1));

        assertThat(item.state()).isEqualTo(WorkItemState.DONE);
        assertThat(item.leaseUntil()).isEmpty();
        assertThat(item.pullEvents()).containsExactly(
                new WorkItemCompleted(item.id(), engagement, firm, Attempt.none(), T0.plusSeconds(1)));
    }

    @Test
    void markDoneDuringALoadKeepsTheLeaseSoTheSlotStaysTaken() {
        WorkItem item = inFlight();

        item.markDone(T0.plusSeconds(1));

        assertThat(item.state()).isEqualTo(WorkItemState.DONE);
        assertThat(item.leaseUntil()).contains(T0.plus(LEASE));
        assertThat(item.pullEvents()).hasSize(1);
    }

    @Test
    void answerAfterMarkDoneOnlyReleasesTheLease() {
        WorkItem item = inFlight();
        item.markDone(T0.plusSeconds(1));
        item.pullEvents();

        item.answer(new Attempt(1), SUCCESS, T0.plusSeconds(60), RETRY);

        assertThat(item.state()).isEqualTo(WorkItemState.DONE);
        assertThat(item.leaseUntil()).isEmpty();
        assertThat(item.pullEvents()).isEmpty();
    }

    @Test
    void answerForAnotherAttemptIsIgnored() {
        WorkItem item = inFlight();

        assertThatNoException().isThrownBy(
                () -> item.answer(new Attempt(2), SUCCESS, T0.plusSeconds(60), RETRY));
        assertThat(item.state()).isEqualTo(WorkItemState.IN_FLIGHT);
        assertThat(item.leaseUntil()).contains(T0.plus(LEASE));
        assertThat(item.pullEvents()).isEmpty();
    }

    @Test
    void answerForAnOutstandingItemIsIgnored() {
        WorkItem item = submitted();

        assertThatNoException().isThrownBy(
                () -> item.answer(new Attempt(1), SUCCESS, T0.plusSeconds(60), RETRY));
        assertThat(item.state()).isEqualTo(WorkItemState.OUTSTANDING);
    }

    @Test
    void answerForADoneItemWithoutALeaseIsIgnored() {
        WorkItem item = inFlight();
        item.answer(new Attempt(1), SUCCESS, T0.plusSeconds(60), RETRY);
        item.pullEvents();

        assertThatNoException().isThrownBy(
                () -> item.answer(new Attempt(1), SUCCESS, T0.plusSeconds(61), RETRY));
        assertThat(item.state()).isEqualTo(WorkItemState.DONE);
        assertThat(item.pullEvents()).isEmpty();
    }

    @Test
    void markDoneOnADoneItemIsRejected() {
        WorkItem item = submitted();
        item.markDone(T0);

        assertThatThrownBy(() -> item.markDone(T0)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void claimOfADoneItemIsRejected() {
        WorkItem item = submitted();
        item.markDone(T0);

        assertThatThrownBy(() -> item.claim(T0, LEASE)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void snapshotAndRestoreRoundTrip() {
        WorkItem.Snapshot snapshot = new WorkItem.Snapshot(
                new WorkItemId(UUID.randomUUID()), engagement, firm, Reason.SWEEP, WorkItemState.IN_FLIGHT,
                new Attempt(2), T0, T0.plusSeconds(30), T0.plus(LEASE), "HTTP_503", 7L);

        WorkItem restored = WorkItem.restore(snapshot);

        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.version()).isEqualTo(7L);
        assertThat(restored.lastError()).contains("HTTP_503");
    }
}
