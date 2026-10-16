package com.caseware.fanout.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.LoadOutcome;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final RetryPolicy POLICY = new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30));

    @Test
    void noAnswerAndNetworkFailuresAreRetryable() {
        assertThat(POLICY.isRetryable(new LoadOutcome.TimedOut())).isTrue();
        assertThat(POLICY.isRetryable(new LoadOutcome.ConnectFailed())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void overloadAndServerErrorsAreRetryable(int status) {
        assertThat(POLICY.isRetryable(new LoadOutcome.Answered(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 410, 422, 301})
    void anyOtherFailedAnswerIsPermanent(int status) {
        assertThat(POLICY.isRetryable(new LoadOutcome.Answered(status))).isFalse();
    }

    @Test
    void theDelayDoublesFromTheBaseAndIsCappedAtTheMaximum() {
        assertThat(POLICY.delayAfter(new Attempt(1))).isEqualTo(Duration.ofSeconds(30));
        assertThat(POLICY.delayAfter(new Attempt(2))).isEqualTo(Duration.ofSeconds(60));
        assertThat(POLICY.delayAfter(new Attempt(3))).isEqualTo(Duration.ofSeconds(120));
        assertThat(POLICY.delayAfter(new Attempt(6))).isEqualTo(Duration.ofSeconds(960));
        assertThat(POLICY.delayAfter(new Attempt(7))).isEqualTo(Duration.ofMinutes(30));
        assertThat(POLICY.delayAfter(new Attempt(100))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aRetryableFailureBelowTheMaximumIsRetriedAfterItsDelay() {
        assertThat(POLICY.decide(new Attempt(2), new LoadOutcome.Answered(503), NOW))
                .isEqualTo(new RetryPolicy.RetryAt(NOW.plusSeconds(60)));
    }

    @Test
    void aRetryableFailureAtTheMaximumIsParked() {
        assertThat(POLICY.decide(new Attempt(6), new LoadOutcome.TimedOut(), NOW)).isEqualTo(new RetryPolicy.Park());
    }

    @Test
    void aPermanentFailureIsParkedAtOnce() {
        assertThat(POLICY.decide(new Attempt(1), new LoadOutcome.Answered(404), NOW)).isEqualTo(new RetryPolicy.Park());
    }

    @Test
    void theDefaultsAreThirtySecondsThirtyMinutesAndSixAttempts() {
        assertThat(RetryPolicy.defaults()).isEqualTo(POLICY);
    }
}
