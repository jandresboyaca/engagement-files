package com.caseware.fanout.application.service;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.SubmitWorkCommand;
import com.caseware.fanout.application.port.in.SubmitWorkUseCase;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.WorkItem;

/**
 * Adds an engagement to the work list (contracts/inbound-ports.md). The repository's one open item
 * per engagement rule decides, not a prior read, so concurrent submits give one item.
 */
public final class SubmitWorkService implements SubmitWorkUseCase {

    private final WorkItemRepositoryPort repository;
    private final ClockPort clock;

    public SubmitWorkService(WorkItemRepositoryPort repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public SubmitResult submit(SubmitWorkCommand command) {
        return repository.insertIfNoOpenItem(
                WorkItem.submit(command.engagementId(), command.firmId(), command.reason(), clock.now()));
    }
}
