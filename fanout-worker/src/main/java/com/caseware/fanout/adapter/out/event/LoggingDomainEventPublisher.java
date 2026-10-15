package com.caseware.fanout.adapter.out.event;

import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.domain.event.DomainEvent;
import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.event.WorkItemParked;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** One log line per domain event, with opaque ids and an error code only (constitution II). No broker. */
@Slf4j
@Component
public class LoggingDomainEventPublisher implements DomainEventPublisherPort {

    @Override
    public void publish(DomainEvent event) {
        switch (event) {
            case WorkItemCompleted e -> log.info("event=WorkItemCompleted workItemId={} engagementId={} firmId={} attempt={}",
                    e.workItemId().value(), e.engagementId().value(), e.firmId().value(), e.attempt().count());
            case WorkItemParked e -> log.info(
                    "event=WorkItemParked workItemId={} engagementId={} firmId={} attempt={} errorCode={}",
                    e.workItemId().value(), e.engagementId().value(), e.firmId().value(), e.attempt().count(),
                    e.errorCode());
        }
    }
}
