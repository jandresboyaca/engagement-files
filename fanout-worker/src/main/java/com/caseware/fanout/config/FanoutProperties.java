package com.caseware.fanout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code fanout.*} settings (plan.md, "Configuration"); invalid values stop startup. */
@ConfigurationProperties("fanout")
@Validated
public record FanoutProperties(
        @Valid @NotNull Limits limits,
        @Valid @NotNull Load load,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Dispatch dispatch,
        @Valid @NotNull Ems ems) {

    /** Initial C and F for {@code capacity_settings}; the row wins once it exists. */
    public record Limits(@PositiveOrZero int global, @PositiveOrZero int perFirm) {

        @AssertTrue(message = "per-firm limit must not exceed the global limit")
        public boolean isPerFirmWithinGlobal() {
            return perFirm <= global;
        }
    }

    /** A positive grace makes the call end before its lease does (research.md section 8). */
    public record Load(
            @NotNull @DurationMin(nanos = 1) Duration timeout,
            @NotNull @DurationMin(nanos = 1) Duration leaseGrace,
            @NotNull @DurationMin(nanos = 1) Duration connectTimeout) {
    }

    public record Retry(
            @Positive int maxAttempts,
            @NotNull @DurationMin(nanos = 1) Duration baseDelay,
            @NotNull @DurationMin(nanos = 1) Duration maxDelay) {

        @AssertTrue(message = "max delay must not be below the base delay")
        public boolean isMaxDelayAtLeastBaseDelay() {
            return baseDelay == null || maxDelay == null || maxDelay.compareTo(baseDelay) >= 0;
        }
    }

    public record Dispatch(@NotNull @DurationMin(nanos = 1) Duration period) {
    }

    public record Ems(@NotNull URI baseUrl) {
    }
}
