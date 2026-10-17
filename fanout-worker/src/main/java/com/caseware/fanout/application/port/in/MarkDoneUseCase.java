package com.caseware.fanout.application.port.in;

import com.caseware.fanout.domain.model.EngagementId;

/** Closes the open item for an engagement (FR-003). See contracts/inbound-ports.md. */
public interface MarkDoneUseCase {

    boolean markDone(EngagementId engagementId);
}
