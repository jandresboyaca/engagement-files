package com.caseware.fanout.domain.event;

import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItemId;
import java.time.Instant;

public record WorkItemParked(WorkItemId workItemId, EngagementId engagementId, FirmId firmId, Attempt attempt,
        String errorCode, Instant at) implements DomainEvent {
}
