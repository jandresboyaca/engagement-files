package com.caseware.fanout.application.fake;

import com.caseware.fanout.application.port.out.CapacitySettingsPort;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;

public final class FakeCapacitySettings implements CapacitySettingsPort {

    private ConcurrencyLimits current;

    public FakeCapacitySettings(ConcurrencyLimits initial) {
        this.current = initial;
    }

    public void set(ConcurrencyLimits limits) {
        this.current = limits;
    }

    @Override
    public ConcurrencyLimits current() {
        return current;
    }

    @Override
    public void initialiseIfAbsent(ConcurrencyLimits fromConfiguration) {
        if (current == null) {
            current = fromConfiguration;
        }
    }
}
