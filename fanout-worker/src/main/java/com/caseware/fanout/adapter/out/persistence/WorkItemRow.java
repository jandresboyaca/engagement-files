package com.caseware.fanout.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code work_item}, in database types. */
record WorkItemRow(UUID id, String engagementId, String firmId, String reason, String state, int attempt,
        Instant addedAt, Instant nextAttemptAt, Instant leaseUntil, String lastError, long version) {
}
