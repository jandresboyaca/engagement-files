package com.caseware.fanout.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code work_item}, the Spring Data JDBC entity. {@code version} is a wrapper type: a
 * null version marks a new row, and Spring Data writes 0 on insert and checks it on every update.
 */
@Table("work_item")
record WorkItemRow(@Id UUID id, String engagementId, String firmId, String reason, String state, int attempt,
        Instant addedAt, Instant nextAttemptAt, Instant leaseUntil, String lastError, @Version Long version,
        Instant updatedAt) {
}
