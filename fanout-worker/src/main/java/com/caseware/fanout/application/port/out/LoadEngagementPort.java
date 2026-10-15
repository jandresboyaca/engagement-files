package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.WorkItemId;

/**
 * Asks EMS to load one engagement. Blocking; translates only; never throws for a downstream
 * answer or a network error. See contracts/outbound-ports.md.
 */
public interface LoadEngagementPort {

    LoadOutcome load(WorkItemId workItemId, FirmId firmId, EngagementId engagementId);
}
