package com.caseware.fanout.application.port.in;

/** Reports the items a previous process left in flight (FR-015). See contracts/inbound-ports.md. */
public interface ResumeAfterRestartUseCase {

    int resume();
}
