package com.caseware.fanout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Regression test by decision (tasks.md T034): the restart behaviour exists after T033. Each
 * "process" is the packaged worker jar in its own JVM, killed with destroyForcibly() while loads
 * are in flight, so no answer of the killed loads is saved: a real crash. SC-003 is checked across
 * 10 crashes from the database and WireMock's request log.
 */
@Testcontainers
class RestartScenarioIT {

    private static final int ITEMS = 40;
    private static final int CRASHES = 10;
    private static final int C = 3;
    private static final Duration EMS_DELAY = Duration.ofMillis(800);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1_500);
    private static final Duration LEASE_GRACE = Duration.ofMillis(100);
    private static final Duration LEASE = READ_TIMEOUT.plus(LEASE_GRACE);
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Path JAR = Path.of("target", "fanout-worker-0.0.1-SNAPSHOT.jar");
    private static final Path LOGS = Path.of("target", "restart-scenario");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static final WireMockServer EMS = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void startEms() {
        EMS.start();
        EMS.stubFor(post(urlPathMatching("/firms/[^/]+/engagements/[^/]+/load"))
                .willReturn(aResponse().withStatus(204).withFixedDelay((int) EMS_DELAY.toMillis())));
    }

    @AfterAll
    static void stopEms() {
        EMS.stop();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void addItems() throws SQLException {
        Instant now = Instant.now();
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement("""
                insert into work_item (id, engagement_id, firm_id, reason, state, attempt, added_at,
                    next_attempt_at, lease_until, last_error, version, updated_at)
                values (?, ?, ?, 'SWEEP', 'OUTSTANDING', 0, ?, ?, null, null, 0, ?)""")) {
            for (int i = 0; i < ITEMS; i++) {
                Timestamp addedAt = Timestamp.from(now.minusSeconds(ITEMS - i));
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, "eng-" + i);
                insert.setString(3, "firm-" + (i % 2));
                insert.setTimestamp(4, addedAt);
                insert.setTimestamp(5, addedAt);
                insert.setTimestamp(6, addedAt);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Map<String, Long> countByState() {
        try (Connection connection = connect();
                ResultSet rows = connection.createStatement()
                        .executeQuery("select state, count(*) from work_item group by state")) {
            Map<String, Long> counts = new java.util.HashMap<>();
            while (rows.next()) {
                counts.put(rows.getString(1), rows.getLong(2));
            }
            return counts;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Process startWorker(int number) throws Exception {
        Files.createDirectories(LOGS);
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", JAR.toString(),
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.docker.compose.enabled=false",
                "--fanout.ems.base-url=" + EMS.baseUrl(),
                "--fanout.limits.global=" + C,
                "--fanout.limits.per-firm=" + C,
                "--fanout.load.timeout=" + READ_TIMEOUT.toMillis() + "ms",
                "--fanout.load.lease-grace=" + LEASE_GRACE.toMillis() + "ms",
                "--fanout.load.connect-timeout=500ms",
                "--fanout.retry.base-delay=50ms",
                "--fanout.retry.max-delay=400ms",
                "--fanout.dispatch.period=50ms");
        File log = LOGS.resolve("worker-" + number + ".log").toFile();
        builder.redirectErrorStream(true).redirectOutput(log);
        return builder.start();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + " (worker logs in " + LOGS + ")");
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

    private static String engagement(ServeEvent event) {
        String url = event.getRequest().getUrl();
        return url.substring(url.indexOf("/engagements/") + "/engagements/".length(), url.lastIndexOf("/load"));
    }

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
    void tenCrashesWithLoadsInFlightLoseNothingAndRepeatOnlyTheLoadsInFlight() throws Exception {
        assertThat(JAR).as("the worker jar, built by mvn package before failsafe runs").exists();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        addItems();

        List<Long> crashes = new ArrayList<>();
        Process worker = null;
        try {
            for (int crash = 1; crash <= CRASHES; crash++) {
                int requestsBefore = EMS.getAllServeEvents().size();
                worker = startWorker(crash);
                await("worker " + crash + " to start loading", () -> EMS.getAllServeEvents().size() > requestsBefore);
                crashes.add(System.currentTimeMillis());
                worker.destroyForcibly().waitFor();
            }
            worker = startWorker(CRASHES + 1);
            await("every item done", () -> countByState().getOrDefault("DONE", 0L) == ITEMS);
        } finally {
            if (worker != null) {
                worker.destroyForcibly().waitFor();
            }
        }
        await("WireMock to finish serving", () -> EMS.getAllServeEvents().stream()
                .allMatch(event -> event.getTiming() != null && event.getTiming().getTotalTime() != null));

        List<ServeEvent> requests = EMS.getAllServeEvents();

        assertThat(countByState()).as("no item lost").isEqualTo(Map.of("DONE", (long) ITEMS));
        assertThat(maxOverlap(requests)).as("concurrent requests at EMS across all processes")
                .isLessThanOrEqualTo(C);

        Map<String, List<ServeEvent>> byEngagement = requests.stream()
                .sorted(Comparator.comparingLong(RestartScenarioIT::start))
                .collect(Collectors.groupingBy(RestartScenarioIT::engagement));
        assertThat(byEngagement).hasSize(ITEMS);
        for (Map.Entry<String, List<ServeEvent>> entry : byEngagement.entrySet()) {
            List<ServeEvent> loads = entry.getValue();
            long cutByACrash = loads.stream()
                    .filter(load -> crashes.stream().anyMatch(crash -> start(load) <= crash
                            && crash < start(load) + EMS_DELAY.toMillis()))
                    .count();
            assertThat(loads).as("loads of %s: once, plus once more per crash during its load", entry.getKey())
                    .hasSize(1 + (int) cutByACrash);
            for (int i = 1; i < loads.size(); i++) {
                assertThat(start(loads.get(i)) - start(loads.get(i - 1)))
                        .as("%s loaded again only after the lease of the crashed load", entry.getKey())
                        .isGreaterThanOrEqualTo(LEASE.toMillis());
            }
        }
        assertThat(crashes).hasSize(CRASHES);
    }
}
