package com.caseware.fanout.application.dto;

import com.caseware.fanout.domain.model.WorkItemId;

public record SubmitResult(WorkItemId workItemId, boolean created) {
}
