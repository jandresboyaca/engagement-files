package com.caseware.fanout.application.fake;

import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.domain.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;

public final class RecordingEventPublisher implements DomainEventPublisherPort {

    private final List<DomainEvent> events = new ArrayList<>();

    @Override
    public synchronized void publish(DomainEvent event) {
        events.add(event);
    }

    public synchronized List<DomainEvent> events() {
        return List.copyOf(events);
    }
}
