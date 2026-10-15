package com.caseware.fanout.application.fake;

import com.caseware.fanout.application.port.out.WorkItemRepositoryContract;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;

class FakeWorkItemRepositoryTest extends WorkItemRepositoryContract {

    @Override
    protected WorkItemRepositoryPort newRepository() {
        return new FakeWorkItemRepository();
    }
}
