package com.caseware.fanout.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.out.time.SystemClockAdapter;
import com.caseware.fanout.application.port.out.WorkItemRepositoryContract;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
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
