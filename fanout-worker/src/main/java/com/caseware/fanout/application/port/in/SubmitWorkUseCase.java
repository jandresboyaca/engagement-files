package com.caseware.fanout.application.port.in;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.SubmitWorkCommand;

/** Adds an engagement to the work list (FR-001, FR-013). See contracts/inbound-ports.md. */
public interface SubmitWorkUseCase {

    SubmitResult submit(SubmitWorkCommand command);
}
