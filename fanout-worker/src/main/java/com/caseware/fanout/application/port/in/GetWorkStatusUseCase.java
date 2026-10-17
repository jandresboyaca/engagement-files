package com.caseware.fanout.application.port.in;

import com.caseware.fanout.application.dto.WorkStatus;

/** Counts for operators (FR-016). See contracts/inbound-ports.md. */
public interface GetWorkStatusUseCase {

    WorkStatus status();
}
