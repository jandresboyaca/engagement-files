package com.caseware.fanout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemState;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Retry then park against a real context, PostgreSQL and WireMock (US3, FR-009 to FR-011). */
@SpringBootTest(properties = "fanout.retry.max-attempts=4")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext
class RetryParkScenarioIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String LOAD = "/firms/[^/]+/engagements/%s/load";
    private static final WireMockServer EMS = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        EMS.start();
    }

    @DynamicPropertySource
    static void emsBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("fanout.ems.base-url", EMS::baseUrl);
    }

    @AfterAll
    static void stopEms() {
        EMS.stop();
    }

    @Autowired
    private WorkItemRepositoryPort repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Value("${fanout.retry.max-attempts}")
    private int maxAttempts;

    @Value("${fanout.retry.base-delay}")
    private Duration baseDelay;

    @Value("${fanout.retry.max-delay}")
    private Duration maxDelay;

    @BeforeEach
    void reset() {
        jdbcClient.sql("delete from work_item").update();
        EMS.resetAll();
        // The same stubs as wiremock/mappings, without delays.
        EMS.stubFor(post(urlPathMatching(LOAD.formatted("eng-missing"))).willReturn(aResponse().withStatus(404)));
        EMS.stubFor(post(urlPathMatching(LOAD.formatted("eng-flaky"))).inScenario("eng-flaky")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("failed-once")
                .willReturn(aResponse().withStatus(503)));
        EMS.stubFor(post(urlPathMatching(LOAD.formatted("eng-flaky"))).inScenario("eng-flaky")
                .whenScenarioStateIs("failed-once").willSetStateTo("failed-twice")
                .willReturn(aResponse().withStatus(503)));
        EMS.stubFor(post(urlPathMatching(LOAD.formatted("eng-flaky"))).inScenario("eng-flaky")
                .whenScenarioStateIs("failed-twice").willReturn(aResponse().withStatus(204)));
        EMS.stubFor(post(urlPathMatching(LOAD.formatted("eng-always-busy"))).willReturn(aResponse().withStatus(503)));
    }

    private WorkItem add(String engagement) {
        WorkItem item = WorkItem.submit(new EngagementId(engagement), new FirmId("firm-a"), Reason.PUBLISH,
                Instant.now());
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private Optional<WorkItem> current(WorkItem item) {
        return repository.findById(item.id());
    }

    private static List<ServeEvent> requestsFor(String engagement) {
        return EMS.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().contains("/engagements/" + engagement + "/"))
                .sorted(Comparator.comparing(event -> event.getRequest().getLoggedDate()))
                .toList();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what);
            }
        }
    }

    private Duration backoffAfter(int attempt) {
        Duration delay = baseDelay.multipliedBy(1L << Math.min(attempt - 1, 30));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    @Test
    void aFlakyEngagementIsDoneAfterTwoRetries() {
        WorkItem flaky = add("eng-flaky");

        await("eng-flaky done", () -> current(flaky).map(WorkItem::state).orElse(null) == WorkItemState.DONE);

        assertThat(requestsFor("eng-flaky")).hasSize(3);
        assertThat(current(flaky).map(WorkItem::attempt)).contains(new Attempt(3));
    }

    @Test
    void aMissingEngagementIsParkedAfterOneRequest() {
        WorkItem missing = add("eng-missing");

        await("eng-missing parked", () -> current(missing).map(WorkItem::state).orElse(null) == WorkItemState.PARKED);

        assertThat(requestsFor("eng-missing")).hasSize(1);
        assertThat(current(missing).flatMap(WorkItem::lastError)).contains("HTTP_404");
    }

    @Test
    void anAlwaysBusyEngagementIsParkedAfterTheMaximumAttemptsWithEachRetryAfterItsBackoff() {
        WorkItem busy = add("eng-always-busy");

        await("eng-always-busy parked", () -> current(busy).map(WorkItem::state).orElse(null) == WorkItemState.PARKED);
        EMS.verify(maxAttempts, postRequestedFor(urlPathMatching(LOAD.formatted("eng-always-busy"))));
        assertThat(current(busy).flatMap(WorkItem::lastError)).contains("HTTP_503");

        List<ServeEvent> requests = requestsFor("eng-always-busy");
        for (int attempt = 1; attempt < requests.size(); attempt++) {
            long gap = requests.get(attempt).getRequest().getLoggedDate().getTime()
                    - requests.get(attempt - 1).getRequest().getLoggedDate().getTime();
            // Logged dates have millisecond precision; allow 1 ms for truncation.
            assertThat(gap).as("gap before attempt %d", attempt + 1)
                    .isGreaterThanOrEqualTo(backoffAfter(attempt).toMillis() - 1);
        }
    }
}
