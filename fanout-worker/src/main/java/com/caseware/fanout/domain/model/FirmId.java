package com.caseware.fanout.domain.model;

public record FirmId(String value) {

    public FirmId {
        OpaqueIds.requireValid(value, "firm id");
    }
}
