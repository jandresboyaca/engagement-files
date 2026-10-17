package com.caseware.fanout.application.port.in;

import com.caseware.fanout.application.dto.DispatchResult;

/** Runs one dispatch cycle (FR-003 to FR-011). See contracts/inbound-ports.md. */
public interface RunDispatchCycleUseCase {

    DispatchResult runOnce();
}
