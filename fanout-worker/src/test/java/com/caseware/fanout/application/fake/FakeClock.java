package com.caseware.fanout.application.fake;

import com.caseware.fanout.application.port.out.ClockPort;
import java.time.Duration;
import java.time.Instant;

public final class FakeClock implements ClockPort {

    private Instant now;

    public FakeClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public Instant now() {
        return now;
    }
}
