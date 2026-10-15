package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.FirmBacklog;
import com.caseware.fanout.domain.policy.InFlightCounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work list in PostgreSQL. Every update is conditional on {@code version}; each method is
 * its own short transaction and none spans a load.
 */
@Repository
@Transactional
public class JdbcWorkItemRepository implements WorkItemRepositoryPort {

    private static final String COLUMNS = """
            id, engagement_id, firm_id, reason, state, attempt, added_at, next_attempt_at, lease_until,
            last_error, version""";
    private static final String OPEN = "state in ('OUTSTANDING', 'IN_FLIGHT')";
    private static final int INSERT_RETRIES = 3;

    private final JdbcClient jdbcClient;
    private final WorkItemRowMapper mapper = Mappers.getMapper(WorkItemRowMapper.class);

    public JdbcWorkItemRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private WorkItem toWorkItem(ResultSet rs, int rowNum) throws SQLException {
        WorkItemRow row = new WorkItemRow(rs.getObject("id", UUID.class), rs.getString("engagement_id"),
                rs.getString("firm_id"), rs.getString("reason"), rs.getString("state"), rs.getInt("attempt"),
                fromDb(rs, "added_at"), fromDb(rs, "next_attempt_at"), fromDb(rs, "lease_until"),
                rs.getString("last_error"), rs.getLong("version"));
        return WorkItem.restore(mapper.toSnapshot(row));
    }

    @Override
    public SubmitResult insertIfNoOpenItem(WorkItem item) {
        WorkItemRow row = mapper.toRow(item.snapshot());
        for (int i = 0; i < INSERT_RETRIES; i++) {
            int inserted = jdbcClient.sql("insert into work_item (" + COLUMNS + ", updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                            + "on conflict (engagement_id) where " + OPEN + " do nothing")
                    .params(row.id(), row.engagementId(), row.firmId(), row.reason(), row.state(), row.attempt(),
                            toDb(row.addedAt()), toDb(row.nextAttemptAt()), toDb(row.leaseUntil()),
                            row.lastError(), row.version())
                    .update();
            if (inserted == 1) {
                return new SubmitResult(item.id(), true);
            }
            Optional<WorkItem> open = findOpenByEngagement(item.engagementId());
            if (open.isPresent()) {
                return new SubmitResult(open.get().id(), false);
            }
        }
        throw new IllegalStateException("could not insert or find an open item for work item " + item.id().value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItem> findOpenByEngagement(EngagementId engagementId) {
        return jdbcClient.sql("select " + COLUMNS + " from work_item where engagement_id = ? and " + OPEN)
                .param(engagementId.value())
                .query(this::toWorkItem)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItem> findById(WorkItemId id) {
        return jdbcClient.sql("select " + COLUMNS + " from work_item where id = ?")
                .param(id.value())
                .query(this::toWorkItem)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public InFlightCounts countInFlight(Instant now) {
        Map<FirmId, Integer> perFirm = new HashMap<>();
        jdbcClient.sql("select firm_id, count(*) as n from work_item where lease_until > ? group by firm_id")
                .param(toDb(now))
                .query(rs -> {
                    perFirm.put(new FirmId(rs.getString("firm_id")), rs.getInt("n"));
                });
        int total = perFirm.values().stream().mapToInt(Integer::intValue).sum();
        return new InFlightCounts(total, Map.copyOf(perFirm));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FirmBacklog> findFirmsWithDueWork(Instant now) {
        return jdbcClient.sql("""
                        select firm_id, min(added_at) as oldest from work_item
                        where state = 'OUTSTANDING' and next_attempt_at <= ? group by firm_id""")
                .param(toDb(now))
                .query((rs, rowNum) -> new FirmBacklog(new FirmId(rs.getString("firm_id")), fromDb(rs, "oldest")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now) {
        return jdbcClient.sql("select " + COLUMNS + """
                         from work_item
                        where firm_id = ? and state = 'OUTSTANDING' and next_attempt_at <= ?
                        order by added_at limit ?""")
                .params(firm.value(), toDb(now), limit)
                .query(this::toWorkItem)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItem> findExpiredLeases(Instant now, int limit) {
        return jdbcClient.sql("select " + COLUMNS + """
                         from work_item
                        where state = 'IN_FLIGHT' and lease_until <= ?
                        order by lease_until limit ?""")
                .params(toDb(now), limit)
                .query(this::toWorkItem)
                .list();
    }

    @Override
    public boolean save(WorkItem item, long expectedVersion) {
        WorkItemRow row = mapper.toRow(item.snapshot());
        int updated = jdbcClient.sql("""
                        update work_item
                           set state = ?, attempt = ?, next_attempt_at = ?, lease_until = ?, last_error = ?,
                               version = version + 1, updated_at = now()
                         where id = ? and version = ?""")
                .params(row.state(), row.attempt(), toDb(row.nextAttemptAt()), toDb(row.leaseUntil()),
                        row.lastError(), row.id(), expectedVersion)
                .update();
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkStatus status(Instant now) {
        Map<WorkItemState, Long> byState = new EnumMap<>(WorkItemState.class);
        Map<FirmId, Map<WorkItemState, Long>> byFirm = new HashMap<>();
        jdbcClient.sql("select firm_id, state, count(*) as n from work_item group by firm_id, state")
                .query(rs -> {
                    WorkItemState state = WorkItemState.valueOf(rs.getString("state"));
                    long n = rs.getLong("n");
                    byState.merge(state, n, Long::sum);
                    byFirm.computeIfAbsent(new FirmId(rs.getString("firm_id")),
                            f -> new EnumMap<>(WorkItemState.class)).put(state, n);
                });
        Optional<Instant> oldest = jdbcClient
                .sql("select min(added_at) as oldest from work_item where state = 'OUTSTANDING'")
                .query((rs, rowNum) -> Optional.ofNullable(fromDb(rs, "oldest")))
                .single();
        return new WorkStatus(Map.copyOf(byState), byFirm.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))),
                countInFlight(now).total(), oldest.map(added -> Duration.between(added, now)));
    }
}
