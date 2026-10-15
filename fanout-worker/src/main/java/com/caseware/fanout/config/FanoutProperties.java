package com.caseware.fanout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
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

    public record Load(@NotNull Duration timeout, @NotNull Duration leaseGrace, @NotNull Duration connectTimeout) {
    }

    public record Retry(@Positive int maxAttempts, @NotNull Duration baseDelay, @NotNull Duration maxDelay) {
    }

    public record Dispatch(@NotNull Duration period) {
    }

    public record Ems(@NotNull URI baseUrl) {
    }
}
