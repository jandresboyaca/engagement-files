package com.caseware.fanout.domain.model;

final class OpaqueIds {

    static final int MAX_LENGTH = 64;

    private OpaqueIds() {
    }

    static void requireValid(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(name + " must be at most " + MAX_LENGTH + " characters");
        }
    }
}
