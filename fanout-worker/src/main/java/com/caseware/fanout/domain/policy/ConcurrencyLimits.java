package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.FirmId;

/** Global cap C and per-firm cap F (FR-004, FR-005). A global cap of 0 pauses new loads. */
public record ConcurrencyLimits(int global, int perFirm) {

    public ConcurrencyLimits {
        if (global < 0 || perFirm < 0) {
            throw new IllegalArgumentException("limits must be >= 0");
        }
        if (perFirm > global) {
            throw new IllegalArgumentException("per-firm limit must not exceed the global limit");
        }
    }

    public int freeGlobal(InFlightCounts inFlight) {
        return Math.max(0, global - inFlight.total());
    }

    public int freeForFirm(FirmId firm, InFlightCounts inFlight) {
        return Math.max(0, perFirm - inFlight.perFirm().getOrDefault(firm, 0));
    }
}
