package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.FirmId;
import java.time.Instant;

/** A firm with due outstanding work and the time its oldest due item was added. */
public record FirmBacklog(FirmId firmId, Instant oldestDueAddedAt) {
}
