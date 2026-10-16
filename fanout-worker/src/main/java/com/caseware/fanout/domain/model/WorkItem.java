package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.event.DomainEvent;
import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.event.WorkItemParked;
import com.caseware.fanout.domain.exception.InvalidTransitionException;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One engagement to load. Owns every state change (data-model.md, "State transitions").
 */
public final class WorkItem {

    /** All fields, for persistence. {@code leaseUntil} and {@code lastError} may be null. */
    public record Snapshot(WorkItemId id, EngagementId engagementId, FirmId firmId, Reason reason,
            WorkItemState state, Attempt attempt, Instant addedAt, Instant nextAttemptAt, Instant leaseUntil,
            String lastError, long version) {
    }

    private final WorkItemId id;
    private final EngagementId engagementId;
    private final FirmId firmId;
    private final Reason reason;
    private final Instant addedAt;
    private final long version;
    private WorkItemState state;
    private Attempt attempt;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private String lastError;
    private final List<DomainEvent> events = new ArrayList<>();

    private WorkItem(Snapshot s) {
        this.id = s.id();
        this.engagementId = s.engagementId();
        this.firmId = s.firmId();
        this.reason = s.reason();
        this.state = s.state();
        this.attempt = s.attempt();
        this.addedAt = s.addedAt();
        this.nextAttemptAt = s.nextAttemptAt();
        this.leaseUntil = s.leaseUntil();
        this.lastError = s.lastError();
        this.version = s.version();
    }

    public static WorkItem submit(EngagementId engagementId, FirmId firmId, Reason reason, Instant now) {
        return new WorkItem(new Snapshot(WorkItemId.newId(), engagementId, firmId, reason,
                WorkItemState.OUTSTANDING, Attempt.none(), now, now, null, null, 0L));
    }

    public static WorkItem restore(Snapshot snapshot) {
        return new WorkItem(snapshot);
    }

    public Snapshot snapshot() {
        return new Snapshot(id, engagementId, firmId, reason, state, attempt, addedAt, nextAttemptAt, leaseUntil,
                lastError, version);
    }

    public void claim(Instant now, Duration leaseDuration) {
        if (state != WorkItemState.OUTSTANDING || nextAttemptAt.isAfter(now)) {
            throw new InvalidTransitionException("claim not allowed in state " + state + " for " + id.value());
        }
        state = WorkItemState.IN_FLIGHT;
        attempt = attempt.next();
        leaseUntil = now.plus(leaseDuration);
    }

    /**
     * Applies the answer of the load claimed at {@code claimedAttempt}. Never throws: an answer that
     * matches no current load is ignored.
     */
    public void answer(Attempt claimedAttempt, LoadOutcome outcome, Instant now, RetryPolicy retryPolicy) {
        if (!attempt.equals(claimedAttempt)) {
            return;
        }
        if (state == WorkItemState.IN_FLIGHT) {
            if (!outcome.isSuccess()) {
                fail(outcome, now, retryPolicy);
                return;
            }
            state = WorkItemState.DONE;
            leaseUntil = null;
            events.add(new WorkItemCompleted(id, engagementId, firmId, attempt, now));
        } else if (state == WorkItemState.DONE && leaseUntil != null) {
            leaseUntil = null;
        }
    }

    /** A load that has not answered by the end of its lease is a timed-out attempt (FR-009). */
    public void expireLease(Instant now, RetryPolicy retryPolicy) {
        if (state != WorkItemState.IN_FLIGHT || leaseUntil.isAfter(now)) {
            throw new InvalidTransitionException("expireLease not allowed before the lease ends for " + id.value());
        }
        fail(new LoadOutcome.TimedOut(), now, retryPolicy);
    }

    private void fail(LoadOutcome failure, Instant now, RetryPolicy retryPolicy) {
        leaseUntil = null;
        lastError = failure.errorCode();
        switch (retryPolicy.decide(attempt, failure, now)) {
            case RetryPolicy.RetryAt retry -> {
                state = WorkItemState.OUTSTANDING;
                nextAttemptAt = retry.at();
            }
            case RetryPolicy.Park park -> {
                state = WorkItemState.PARKED;
                events.add(new WorkItemParked(id, engagementId, firmId, attempt, lastError, now));
            }
        }
    }

    /** Closes the item. From {@code IN_FLIGHT} the lease is kept: the load is still running. */
    public void markDone(Instant now) {
        if (state != WorkItemState.OUTSTANDING && state != WorkItemState.IN_FLIGHT) {
            throw new InvalidTransitionException("markDone not allowed in state " + state + " for " + id.value());
        }
        state = WorkItemState.DONE;
        events.add(new WorkItemCompleted(id, engagementId, firmId, attempt, now));
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public WorkItemId id() {
        return id;
    }

    public EngagementId engagementId() {
        return engagementId;
    }

    public FirmId firmId() {
        return firmId;
    }

    public Reason reason() {
        return reason;
    }

    public WorkItemState state() {
        return state;
    }

    public Attempt attempt() {
        return attempt;
    }

    public Instant addedAt() {
        return addedAt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Optional<Instant> leaseUntil() {
        return Optional.ofNullable(leaseUntil);
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public long version() {
        return version;
    }
}
