package com.caseware.fanout.domain.model;

/**
 * Neutral result of one load as the downstream adapter saw it. Whether a failure is retryable is
 * decided by {@code RetryPolicy}, not here.
 */
public sealed interface LoadOutcome {

    default boolean isSuccess() {
        return false;
    }

    String errorCode();

    record Answered(int status) implements LoadOutcome {

        @Override
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        @Override
        public String errorCode() {
            return "HTTP_" + status;
        }
    }

    record TimedOut() implements LoadOutcome {

        @Override
        public String errorCode() {
            return "TIMEOUT";
        }
    }

    record ConnectFailed() implements LoadOutcome {

        @Override
        public String errorCode() {
            return "CONNECT";
        }
    }
}
