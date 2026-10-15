package com.caseware.fanout.application.port.out;

import java.time.Instant;

public interface ClockPort {

    Instant now();
}
