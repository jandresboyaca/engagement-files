package com.caseware.fanout.domain.model;

public record EngagementId(String value) {

    public EngagementId {
        OpaqueIds.requireValid(value, "engagement id");
    }
}
