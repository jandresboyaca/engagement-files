package com.caseware.fanout.domain.policy;

/** Global cap C and per-firm cap F. Behaviour is added in T021. */
public record ConcurrencyLimits(int global, int perFirm) {
}
