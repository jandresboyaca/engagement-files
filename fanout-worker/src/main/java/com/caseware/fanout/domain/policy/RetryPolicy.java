package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.LoadOutcome;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides what follows a failed load (research.md section 4). Retryable: no answer, a network
 * failure, 429 or any 5xx. Anything else is permanent. A retryable failure is tried again after
 * {@code min(base × 2^(attempt − 1), max)}, until the maximum number of attempts.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    private static final int MAX_DOUBLINGS = 30;

    public sealed interface Decision permits RetryAt, Park {
    }

    public record RetryAt(Instant at) implements Decision {
    }

    public record Park() implements Decision {
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30));
    }

    public boolean isRetryable(LoadOutcome outcome) {
        return switch (outcome) {
            case LoadOutcome.TimedOut timedOut -> true;
            case LoadOutcome.ConnectFailed connectFailed -> true;
            case LoadOutcome.Answered answered -> answered.status() == 429 || answered.status() >= 500;
        };
    }

    public Duration delayAfter(Attempt attempt) {
        Duration delay = baseDelay.multipliedBy(1L << Math.min(attempt.count() - 1, MAX_DOUBLINGS));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    public Decision decide(Attempt attempt, LoadOutcome failure, Instant now) {
        if (!isRetryable(failure) || attempt.count() >= maxAttempts) {
            return new Park();
        }
        return new RetryAt(now.plus(delayAfter(attempt)));
    }
}
