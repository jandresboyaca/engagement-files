package com.caseware.fanout.domain.model;

import java.util.UUID;

public record WorkItemId(UUID value) {

    public WorkItemId {
        if (value == null) {
            throw new IllegalArgumentException("work item id must not be null");
        }
    }

    public static WorkItemId newId() {
        return new WorkItemId(UUID.randomUUID());
    }
}
