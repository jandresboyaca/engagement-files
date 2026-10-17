package com.caseware.fanout.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.out.time.SystemClockAdapter;
import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.port.out.WorkItemRepositoryContract;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the repository contract against PostgreSQL. The contract's test methods are declared in
 * another class, so @DataJdbcTest's rollback does not cover them; newRepository() empties the table.
 */
@DataJdbcTest
@Import({JdbcWorkItemRepository.class, SystemClockAdapter.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DirtiesContext
class JdbcWorkItemRepositoryIT extends WorkItemRepositoryContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcWorkItemRepository adapter;

    @Override
    protected WorkItemRepositoryPort newRepository() {
        jdbcClient.sql("delete from work_item").update();
        return adapter;
    }

    private void insertRaw(String state, Timestamp leaseUntil) {
        Timestamp now = Timestamp.from(NOW);
        jdbcClient.sql("""
                insert into work_item (id, engagement_id, firm_id, reason, state, attempt, added_at,
                    next_attempt_at, lease_until, last_error, version, updated_at)
                values (?, ?, 'firm-a', 'PUBLISH', ?, 1, ?, ?, ?, null, 0, ?)
                """)
                .params(UUID.randomUUID(), "eng-" + UUID.randomUUID(), state, now, now, leaseUntil, now)
                .update();
    }

    // contracts/inbound-ports.md: submit is idempotent under concurrent calls (review M5). Outside a
    // test transaction, so every thread sees the others' committed rows.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSubmitsForOneEngagementCreateOneItemAndAllReturnItsId() throws Exception {
        newRepository();
        int threads = 16;
        CyclicBarrier start = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int round = 0; round < 20; round++) {
                EngagementId engagement = new EngagementId("eng-race-" + round);
                List<Future<SubmitResult>> submits = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    submits.add(pool.submit(() -> {
                        start.await();
                        return adapter.insertIfNoOpenItem(
                                WorkItem.submit(engagement, new FirmId("firm-a"), Reason.PUBLISH, NOW));
                    }));
                }
                List<SubmitResult> results = new ArrayList<>();
                for (Future<SubmitResult> submit : submits) {
                    results.add(submit.get());
                }

                assertThat(results).as("round %d: created", round).filteredOn(SubmitResult::created).hasSize(1);
                assertThat(results).as("round %d: ids", round).extracting(SubmitResult::workItemId).containsOnly(
                        results.stream().filter(SubmitResult::created).findFirst().orElseThrow().workItemId());
                assertThat(jdbcClient.sql("select count(*) from work_item where engagement_id = ?")
                        .param(engagement.value()).query(Long.class).single()).as("round %d: rows", round).isEqualTo(1);
            }
        }
    }

    @Test
    void anInFlightRowWithoutALeaseIsRejected() {
        assertThatThrownBy(() -> insertRaw("IN_FLIGHT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOutstandingRowWithALeaseIsRejected() {
        assertThatThrownBy(() -> insertRaw("OUTSTANDING", Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aParkedRowWithALeaseIsRejected() {
        assertThatThrownBy(() -> insertRaw("PARKED", Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
