package com.caseware.fanout.domain.event;

public sealed interface DomainEvent permits WorkItemCompleted, WorkItemParked {
}
