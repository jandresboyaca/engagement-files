package com.caseware.fanout.application.dto;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;

public record SubmitWorkCommand(EngagementId engagementId, FirmId firmId, Reason reason) {
}
