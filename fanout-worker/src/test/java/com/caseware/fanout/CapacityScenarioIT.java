package com.caseware.fanout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** SC-001, FR-003 with FR-004, FR-007 against a real context, PostgreSQL and WireMock. */
@SpringBootTest(properties = {"fanout.limits.global=3", "fanout.limits.per-firm=3"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CapacityScenarioIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
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

    @Autowired
    private PostgreSQLContainer postgres;

    @BeforeEach
    void reset() {
        jdbcClient.sql("delete from work_item").update();
        EMS.resetAll();
        stubLoads(300);
    }

    private static void stubLoads(int delayMillis) {
        EMS.stubFor(post(urlPathMatching("/firms/[^/]+/engagements/[^/]+/load"))
                .willReturn(aResponse().withStatus(204).withFixedDelay(delayMillis)));
    }

    private Optional<ConcurrencyLimits> storedLimits() {
        return jdbcClient.sql("select global_limit, per_firm_limit from capacity_settings where id = 1")
                .query((rs, rowNum) -> new ConcurrencyLimits(rs.getInt(1), rs.getInt(2)))
                .optional();
    }

    private void setLimits(int global, int perFirm) {
        jdbcClient.sql("update capacity_settings set global_limit = ?, per_firm_limit = ?, updated_at = now()")
                .params(global, perFirm)
                .update();
    }

    private WorkItem add(String engagement, String firm, Instant addedAt) {
        WorkItem item = WorkItem.submit(new EngagementId(engagement), new FirmId(firm), Reason.PUBLISH, addedAt);
        repository.insertIfNoOpenItem(item);
        return item;
    }

    private long done() {
        return repository.status(Instant.now()).countsByState().getOrDefault(WorkItemState.DONE, 0L);
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

    private static long start(ServeEvent event) {
        return event.getRequest().getLoggedDate().getTime();
    }

    private static long end(ServeEvent event) {
        return start(event) + event.getTiming().getTotalTime();
    }

    /** Largest number of requests WireMock was serving at the same time; an end before a start at a tie. */
    private static int maxOverlap(List<ServeEvent> events) {
        List<long[]> points = new ArrayList<>();
        for (ServeEvent event : events) {
            points.add(new long[] {start(event), +1});
            points.add(new long[] {end(event), -1});
        }
        points.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
        int current = 0;
        int max = 0;
        for (long[] point : points) {
            current += (int) point[1];
            max = Math.max(max, current);
        }
        return max;
    }

    @Test
    @Order(1)
    void aFreshDatabaseHoldsTheConfiguredLimits() {
        assertThat(storedLimits()).contains(new ConcurrencyLimits(3, 3));
    }

    @Test
    @Order(2)
    void startupDoesNotOverwriteAnExistingRow() {
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(FanoutWorkerApplication.class)
                .profiles("test")
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.docker.compose.enabled=false",
                        "fanout.ems.base-url=" + EMS.baseUrl(),
                        "fanout.limits.global=50",
                        "fanout.limits.per-firm=10")
                .run()) {
            assertThat(second.isActive()).isTrue();
        }

        assertThat(storedLimits()).contains(new ConcurrencyLimits(3, 3));
    }

    @Test
    @Order(3)
    void neverMoreThanTheGlobalCapOfRequestsAtTheSameTime() {
        setLimits(3, 3);
        Instant now = Instant.now();
        for (int i = 0; i < 30; i++) {
            add("eng-" + i, "firm-" + (i % 3), now.minusSeconds(60 - i));
        }

        await("30 items done", () -> done() == 30);

        List<ServeEvent> served = EMS.getAllServeEvents();
        assertThat(served).hasSize(30);
        assertThat(maxOverlap(served)).isLessThanOrEqualTo(3);
    }

    @Test
    @Order(4)
    void anItemMarkedDoneDuringItsLoadKeepsItsSlotUntilEmsAnswers() {
        setLimits(1, 1);
        EMS.resetAll();
        stubLoads(400);
        Instant now = Instant.now();
        WorkItem first = add("eng-first", "firm-a", now.minusSeconds(2));
        add("eng-second", "firm-a", now.minusSeconds(1));

        await("the first load to reach EMS", () -> !EMS.getAllServeEvents().isEmpty());
        boolean marked = false;
        for (int i = 0; i < 3 && !marked; i++) {
            Optional<WorkItem> open = repository.findOpenByEngagement(first.engagementId());
            if (open.isEmpty()) {
                break;
            }
            WorkItem item = open.get();
            long version = item.version();
            item.markDone(Instant.now());
            marked = repository.save(item, version);
        }
        assertThat(marked).as("first item marked done while its load was running").isTrue();

        await("both items done", () -> done() == 2);
        await("both loads served", () -> EMS.getAllServeEvents().size() == 2);

        List<ServeEvent> served = new ArrayList<>(EMS.getAllServeEvents());
        served.sort(Comparator.comparingLong(CapacityScenarioIT::start));
        assertThat(served.get(0).getRequest().getUrl()).contains("eng-first");
        assertThat(start(served.get(1))).isGreaterThanOrEqualTo(end(served.get(0)));
    }
}
