package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.policy.ConcurrencyLimits;

/** C and F, changeable while running (FR-007). See contracts/outbound-ports.md. */
public interface CapacitySettingsPort {

    ConcurrencyLimits current();

    void initialiseIfAbsent(ConcurrencyLimits fromConfiguration);
}
