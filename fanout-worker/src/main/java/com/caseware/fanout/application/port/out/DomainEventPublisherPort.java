package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.event.DomainEvent;

/** Publishes domain events; the production adapter logs opaque ids only. */
public interface DomainEventPublisherPort {

    void publish(DomainEvent event);
}
