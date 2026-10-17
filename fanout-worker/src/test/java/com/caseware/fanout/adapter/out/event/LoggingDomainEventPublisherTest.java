package com.caseware.fanout.adapter.out.event;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.caseware.fanout.domain.event.WorkItemCompleted;
import com.caseware.fanout.domain.event.WorkItemParked;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItemId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingDomainEventPublisherTest {

    private static final WorkItemId ID = new WorkItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final Instant AT = Instant.parse("2026-10-14T10:00:00Z");

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingDomainEventPublisher.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void aCompletedEventIsOneLineWithOpaqueIdsOnly() {
        new LoggingDomainEventPublisher().publish(
                new WorkItemCompleted(ID, new EngagementId("eng-1"), new FirmId("firm-1"), new Attempt(2), AT));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "event=WorkItemCompleted workItemId=00000000-0000-0000-0000-000000000001"
                        + " engagementId=eng-1 firmId=firm-1 attempt=2");
    }

    @Test
    void aParkedEventAddsOnlyItsErrorCode() {
        new LoggingDomainEventPublisher().publish(new WorkItemParked(ID, new EngagementId("eng-1"),
                new FirmId("firm-1"), new Attempt(6), "HTTP_503", AT));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "event=WorkItemParked workItemId=00000000-0000-0000-0000-000000000001"
                        + " engagementId=eng-1 firmId=firm-1 attempt=6 errorCode=HTTP_503");
    }
}
