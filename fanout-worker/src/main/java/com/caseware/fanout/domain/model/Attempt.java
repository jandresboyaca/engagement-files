package com.caseware.fanout.domain.model;

public record Attempt(int count) {

    public Attempt {
        if (count < 0) {
            throw new IllegalArgumentException("attempt count must be >= 0");
        }
    }

    public static Attempt none() {
        return new Attempt(0);
    }

    public Attempt next() {
        return new Attempt(count + 1);
    }
}
