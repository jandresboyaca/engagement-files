package com.caseware.fanout.application.fake;

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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory work list with the same rules as the database: one open item per engagement,
 * conditional saves on version, in flight = unexpired lease in any state. Checked by
 * {@code WorkItemRepositoryContract}. Stores snapshots so callers never share mutable items.
 */
public final class FakeWorkItemRepository implements WorkItemRepositoryPort {

    private final Map<WorkItemId, WorkItem.Snapshot> rows = new LinkedHashMap<>();

    private static boolean isOpen(WorkItem.Snapshot s) {
        return s.state() == WorkItemState.OUTSTANDING || s.state() == WorkItemState.IN_FLIGHT;
    }

    private static boolean isDue(WorkItem.Snapshot s, Instant now) {
        return s.state() == WorkItemState.OUTSTANDING && !s.nextAttemptAt().isAfter(now);
    }

    private static boolean holdsSlot(WorkItem.Snapshot s, Instant now) {
        return s.leaseUntil() != null && s.leaseUntil().isAfter(now);
    }

    private List<WorkItem.Snapshot> select(Predicate<WorkItem.Snapshot> filter) {
        return rows.values().stream().filter(filter).toList();
    }

    @Override
    public synchronized SubmitResult insertIfNoOpenItem(WorkItem item) {
        Optional<WorkItem.Snapshot> open = rows.values().stream()
                .filter(s -> s.engagementId().equals(item.engagementId()) && isOpen(s))
                .findFirst();
        if (open.isPresent() && isOpen(item.snapshot())) {
            return new SubmitResult(open.get().id(), false);
        }
        rows.put(item.id(), item.snapshot());
        return new SubmitResult(item.id(), true);
    }

    @Override
    public synchronized Optional<WorkItem> findOpenByEngagement(EngagementId engagementId) {
        return rows.values().stream()
                .filter(s -> s.engagementId().equals(engagementId) && isOpen(s))
                .findFirst()
                .map(WorkItem::restore);
    }

    @Override
    public synchronized Optional<WorkItem> findById(WorkItemId id) {
        return Optional.ofNullable(rows.get(id)).map(WorkItem::restore);
    }

    @Override
    public synchronized InFlightCounts countInFlight(Instant now) {
        Map<FirmId, Integer> perFirm = new HashMap<>();
        select(s -> holdsSlot(s, now)).forEach(s -> perFirm.merge(s.firmId(), 1, Integer::sum));
        int total = perFirm.values().stream().mapToInt(Integer::intValue).sum();
        return new InFlightCounts(total, Map.copyOf(perFirm));
    }

    @Override
    public synchronized List<FirmBacklog> findFirmsWithDueWork(Instant now) {
        return select(s -> isDue(s, now)).stream()
                .collect(Collectors.toMap(WorkItem.Snapshot::firmId, WorkItem.Snapshot::addedAt,
                        (a, b) -> a.isBefore(b) ? a : b))
                .entrySet().stream()
                .map(e -> new FirmBacklog(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public synchronized List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now) {
        return select(s -> s.firmId().equals(firm) && isDue(s, now)).stream()
                .sorted(Comparator.comparing(WorkItem.Snapshot::addedAt))
                .limit(limit)
                .map(WorkItem::restore)
                .toList();
    }

    @Override
    public synchronized List<WorkItem> findExpiredLeases(Instant now, int limit) {
        return select(s -> s.state() == WorkItemState.IN_FLIGHT && !s.leaseUntil().isAfter(now)).stream()
                .limit(limit)
                .map(WorkItem::restore)
                .toList();
    }

    @Override
    public synchronized boolean save(WorkItem item, long expectedVersion) {
        WorkItem.Snapshot stored = rows.get(item.id());
        if (stored == null || stored.version() != expectedVersion) {
            return false;
        }
        WorkItem.Snapshot s = item.snapshot();
        rows.put(item.id(), new WorkItem.Snapshot(s.id(), s.engagementId(), s.firmId(), s.reason(), s.state(),
                s.attempt(), s.addedAt(), s.nextAttemptAt(), s.leaseUntil(), s.lastError(), expectedVersion + 1));
        return true;
    }

    @Override
    public synchronized WorkStatus status(Instant now) {
        Map<WorkItemState, Long> byState = new EnumMap<>(WorkItemState.class);
        Map<FirmId, Map<WorkItemState, Long>> byFirm = new HashMap<>();
        for (WorkItem.Snapshot s : rows.values()) {
            byState.merge(s.state(), 1L, Long::sum);
            byFirm.computeIfAbsent(s.firmId(), f -> new EnumMap<>(WorkItemState.class)).merge(s.state(), 1L, Long::sum);
        }
        Optional<Duration> oldestAge = select(s -> s.state() == WorkItemState.OUTSTANDING).stream()
                .map(WorkItem.Snapshot::addedAt)
                .min(Comparator.naturalOrder())
                .map(added -> Duration.between(added, now));
        return new WorkStatus(Map.copyOf(byState), byFirm.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))),
                countInFlight(now).total(), oldestAge);
    }
}
