package com.caseware.fanout.domain.policy;

import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
}
