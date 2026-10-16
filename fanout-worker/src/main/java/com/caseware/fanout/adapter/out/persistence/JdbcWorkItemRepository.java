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
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work list on Spring Data JDBC. Each port method is one repository call, and so its own short
 * transaction; none spans a load. Updates are conditional on {@code @Version}.
 */
@Component
@RequiredArgsConstructor
public class JdbcWorkItemRepository implements WorkItemRepositoryPort {

    private static final List<String> OPEN = List.of(WorkItemState.OUTSTANDING.name(), WorkItemState.IN_FLIGHT.name());
    private static final int INSERT_RETRIES = 3;

    private final WorkItemRowRepository rows;
    private final WorkItemRowMapper mapper = Mappers.getMapper(WorkItemRowMapper.class);

    private WorkItem toWorkItem(WorkItemRow row) {
        return WorkItem.restore(mapper.toSnapshot(row));
    }

    /** Inserts, or returns the open item that the partial unique index on engagement_id reports. */
    @Override
    public SubmitResult insertIfNoOpenItem(WorkItem item) {
        for (int i = 0; i < INSERT_RETRIES; i++) {
            try {
                rows.save(mapper.toNewRow(item.snapshot(), Instant.now()));
                return new SubmitResult(item.id(), true);
            } catch (DuplicateKeyException openItemExists) {
                Optional<WorkItem> open = findOpenByEngagement(item.engagementId());
                if (open.isPresent()) {
                    return new SubmitResult(open.get().id(), false);
                }
            }
        }
        throw new IllegalStateException("could not insert or find an open item for work item " + item.id().value());
    }

    @Override
    public Optional<WorkItem> findOpenByEngagement(EngagementId engagementId) {
        return rows.findFirstByEngagementIdAndStateIn(engagementId.value(), OPEN).map(this::toWorkItem);
    }

    @Override
    public Optional<WorkItem> findById(WorkItemId id) {
        return rows.findById(id.value()).map(this::toWorkItem);
    }

    @Override
    public InFlightCounts countInFlight(Instant now) {
        Map<FirmId, Integer> perFirm = rows.countInFlightPerFirm(now).stream()
                .collect(Collectors.toUnmodifiableMap(f -> new FirmId(f.firmId()), f -> (int) f.loads()));
        return new InFlightCounts(perFirm.values().stream().mapToInt(Integer::intValue).sum(), perFirm);
    }

    @Override
    public List<FirmBacklog> findFirmsWithDueWork(Instant now) {
        return rows.findFirmsWithDueWork(now).stream()
                .map(f -> new FirmBacklog(new FirmId(f.firmId()), f.oldestDueAddedAt()))
                .toList();
    }

    @Override
    public List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now) {
        return rows.findByFirmIdAndStateAndNextAttemptAtLessThanEqualOrderByAddedAtAsc(firm.value(),
                        WorkItemState.OUTSTANDING.name(), now, Limit.of(limit))
                .stream().map(this::toWorkItem).toList();
    }

    @Override
    public List<WorkItem> findExpiredLeases(Instant now, int limit) {
        return rows.findByStateAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(WorkItemState.IN_FLIGHT.name(), now,
                        Limit.of(limit))
                .stream().map(this::toWorkItem).toList();
    }

    @Override
    public boolean save(WorkItem item, long expectedVersion) {
        try {
            rows.save(mapper.toRow(item.snapshot(), expectedVersion, Instant.now()));
            return true;
        } catch (OptimisticLockingFailureException changedSinceRead) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public WorkStatus status(Instant now) {
        Map<WorkItemState, Long> byState = new EnumMap<>(WorkItemState.class);
        Map<FirmId, Map<WorkItemState, Long>> byFirm = new HashMap<>();
        for (WorkItemRowRepository.FirmStateCount count : rows.countPerFirmAndState()) {
            WorkItemState state = WorkItemState.valueOf(count.state());
            byState.merge(state, count.items(), Long::sum);
            byFirm.computeIfAbsent(new FirmId(count.firmId()), f -> new EnumMap<>(WorkItemState.class))
                    .put(state, count.items());
        }
        Optional<Duration> oldestAge = rows.findOldestOutstandingAddedAt().map(added -> Duration.between(added, now));
        return new WorkStatus(Map.copyOf(byState), byFirm.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))),
                countInFlight(now).total(), oldestAge);
    }
}
