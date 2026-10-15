package com.caseware.fanout.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.FirmBacklog;
import com.caseware.fanout.domain.policy.InFlightCounts;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole contract of {@link WorkItemRepositoryPort} (contracts/outbound-ports.md), run against
 * the in-memory fake and against the JDBC adapter so the two cannot drift.
 */
public abstract class WorkItemRepositoryContract {

    protected static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final FirmId FIRM_A = new FirmId("firm-a");
    private static final FirmId FIRM_B = new FirmId("firm-b");

    private WorkItemRepositoryPort repository;

    /** Returns an empty repository; called before every test. */
    protected abstract WorkItemRepositoryPort newRepository();

    @BeforeEach
    void setUpRepository() {
        repository = newRepository();
    }

    private static WorkItem item(String engagement, FirmId firm, WorkItemState state, Instant addedAt,
            Instant nextAttemptAt, Instant leaseUntil) {
        int attempt = state == WorkItemState.OUTSTANDING && leaseUntil == null ? 0 : 1;
        return WorkItem.restore(new WorkItem.Snapshot(new WorkItemId(UUID.randomUUID()),
                new EngagementId(engagement), firm, Reason.PUBLISH, state, new Attempt(attempt), addedAt,
                nextAttemptAt, leaseUntil, null, 0L));
    }

    private static WorkItem outstanding(String engagement, FirmId firm, Instant addedAt) {
        return item(engagement, firm, WorkItemState.OUTSTANDING, addedAt, addedAt, null);
    }

    private static WorkItem inFlight(String engagement, FirmId firm, Instant leaseUntil) {
        return item(engagement, firm, WorkItemState.IN_FLIGHT, NOW.minusSeconds(600), NOW.minusSeconds(600),
                leaseUntil);
    }

    private static WorkItem done(String engagement, FirmId firm, Instant leaseUntil) {
        return item(engagement, firm, WorkItemState.DONE, NOW.minusSeconds(600), NOW.minusSeconds(600),
                leaseUntil);
    }

    private static WorkItem parked(String engagement, FirmId firm) {
        return item(engagement, firm, WorkItemState.PARKED, NOW.minusSeconds(600), NOW.minusSeconds(600), null);
    }

    @Test
    void insertCreatesANewItemThatCanBeFoundById() {
        WorkItem item = outstanding("eng-1", FIRM_A, NOW);

        SubmitResult result = repository.insertIfNoOpenItem(item);

        assertThat(result).isEqualTo(new SubmitResult(item.id(), true));
        assertThat(repository.findById(item.id())).map(WorkItem::snapshot).contains(item.snapshot());
    }

    @Test
    void insertReturnsTheOpenOutstandingItemInsteadOfCreatingASecond() {
        WorkItem first = outstanding("eng-1", FIRM_A, NOW);
        repository.insertIfNoOpenItem(first);

        SubmitResult result = repository.insertIfNoOpenItem(outstanding("eng-1", FIRM_A, NOW.plusSeconds(1)));

        assertThat(result).isEqualTo(new SubmitResult(first.id(), false));
        assertThat(repository.status(NOW).countsByState()).containsEntry(WorkItemState.OUTSTANDING, 1L);
    }

    @Test
    void insertReturnsTheOpenInFlightItemInsteadOfCreatingASecond() {
        WorkItem first = inFlight("eng-1", FIRM_A, NOW.plusSeconds(60));
        repository.insertIfNoOpenItem(first);

        SubmitResult result = repository.insertIfNoOpenItem(outstanding("eng-1", FIRM_A, NOW));

        assertThat(result).isEqualTo(new SubmitResult(first.id(), false));
    }

    @Test
    void insertCreatesANewItemWhenOnlyDoneOrParkedItemsExist() {
        repository.insertIfNoOpenItem(done("eng-1", FIRM_A, null));
        repository.insertIfNoOpenItem(parked("eng-2", FIRM_A));
        WorkItem again1 = outstanding("eng-1", FIRM_A, NOW);
        WorkItem again2 = outstanding("eng-2", FIRM_A, NOW);

        assertThat(repository.insertIfNoOpenItem(again1)).isEqualTo(new SubmitResult(again1.id(), true));
        assertThat(repository.insertIfNoOpenItem(again2)).isEqualTo(new SubmitResult(again2.id(), true));
    }

    @Test
    void findOpenByEngagementReturnsOnlyAnOpenItem() {
        WorkItem open = outstanding("eng-1", FIRM_A, NOW);
        repository.insertIfNoOpenItem(done("eng-1", FIRM_A, null));
        repository.insertIfNoOpenItem(open);
        repository.insertIfNoOpenItem(done("eng-2", FIRM_A, null));

        assertThat(repository.findOpenByEngagement(new EngagementId("eng-1"))).map(WorkItem::id)
                .contains(open.id());
        assertThat(repository.findOpenByEngagement(new EngagementId("eng-2"))).isEmpty();
    }

    @Test
    void saveWithTheCurrentVersionWritesAllFieldsAndIncrementsTheVersion() {
        WorkItem item = outstanding("eng-1", FIRM_A, NOW);
        repository.insertIfNoOpenItem(item);
        item.claim(NOW, Duration.ofMinutes(5));

        boolean saved = repository.save(item, 0L);

        assertThat(saved).isTrue();
        WorkItem stored = repository.findById(item.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(WorkItemState.IN_FLIGHT);
        assertThat(stored.attempt()).isEqualTo(new Attempt(1));
        assertThat(stored.leaseUntil()).contains(NOW.plus(Duration.ofMinutes(5)));
        assertThat(stored.version()).isEqualTo(1L);
    }

    @Test
    void saveWithAStaleVersionChangesNothing() {
        WorkItem item = outstanding("eng-1", FIRM_A, NOW);
        repository.insertIfNoOpenItem(item);
        WorkItem copy = WorkItem.restore(item.snapshot());
        copy.markDone(NOW);
        repository.save(copy, 0L);
        item.claim(NOW, Duration.ofMinutes(5));

        boolean saved = repository.save(item, 0L);

        assertThat(saved).isFalse();
        assertThat(repository.findById(item.id())).map(WorkItem::state).contains(WorkItemState.DONE);
    }

    @Test
    void countInFlightCountsUnexpiredLeasesInAnyStateTotalAndPerFirm() {
        repository.insertIfNoOpenItem(inFlight("eng-1", FIRM_A, NOW.plusSeconds(60)));
        repository.insertIfNoOpenItem(inFlight("eng-2", FIRM_A, NOW.plusSeconds(60)));
        repository.insertIfNoOpenItem(done("eng-3", FIRM_B, NOW.plusSeconds(60)));
        repository.insertIfNoOpenItem(inFlight("eng-4", FIRM_B, NOW));
        repository.insertIfNoOpenItem(done("eng-5", FIRM_B, NOW.minusSeconds(1)));
        repository.insertIfNoOpenItem(outstanding("eng-6", FIRM_B, NOW));

        InFlightCounts counts = repository.countInFlight(NOW);

        assertThat(counts.total()).isEqualTo(3);
        assertThat(counts.perFirm()).isEqualTo(Map.of(FIRM_A, 2, FIRM_B, 1));
    }

    @Test
    void findFirmsWithDueWorkReturnsEachFirmWithItsOldestDueItem() {
        repository.insertIfNoOpenItem(outstanding("eng-1", FIRM_A, NOW.minusSeconds(30)));
        repository.insertIfNoOpenItem(outstanding("eng-2", FIRM_A, NOW.minusSeconds(90)));
        repository.insertIfNoOpenItem(outstanding("eng-3", FIRM_B, NOW.minusSeconds(10)));
        repository.insertIfNoOpenItem(
                item("eng-4", FIRM_B, WorkItemState.OUTSTANDING, NOW.minusSeconds(500), NOW.plusSeconds(60), null));
        repository.insertIfNoOpenItem(
                item("eng-5", new FirmId("firm-c"), WorkItemState.OUTSTANDING, NOW, NOW.plusSeconds(1), null));
        repository.insertIfNoOpenItem(inFlight("eng-6", new FirmId("firm-d"), NOW.plusSeconds(60)));

        assertThat(repository.findFirmsWithDueWork(NOW)).containsExactlyInAnyOrder(
                new FirmBacklog(FIRM_A, NOW.minusSeconds(90)),
                new FirmBacklog(FIRM_B, NOW.minusSeconds(10)));
    }

    @Test
    void findOldestDueReturnsDueOutstandingItemsOfOneFirmOldestFirstUpToTheLimit() {
        WorkItem newest = outstanding("eng-1", FIRM_A, NOW.minusSeconds(10));
        WorkItem oldest = outstanding("eng-2", FIRM_A, NOW.minusSeconds(30));
        WorkItem middle = outstanding("eng-3", FIRM_A, NOW.minusSeconds(20));
        repository.insertIfNoOpenItem(newest);
        repository.insertIfNoOpenItem(oldest);
        repository.insertIfNoOpenItem(middle);
        repository.insertIfNoOpenItem(
                item("eng-4", FIRM_A, WorkItemState.OUTSTANDING, NOW.minusSeconds(99), NOW.plusSeconds(5), null));
        repository.insertIfNoOpenItem(outstanding("eng-5", FIRM_B, NOW.minusSeconds(99)));

        assertThat(repository.findOldestDue(FIRM_A, 2, NOW)).extracting(WorkItem::id)
                .containsExactly(oldest.id(), middle.id());
    }

    @Test
    void findExpiredLeasesReturnsOnlyInFlightItemsWhoseLeaseHasEnded() {
        WorkItem expired = inFlight("eng-1", FIRM_A, NOW);
        WorkItem expiredEarlier = inFlight("eng-2", FIRM_B, NOW.minusSeconds(5));
        repository.insertIfNoOpenItem(expired);
        repository.insertIfNoOpenItem(expiredEarlier);
        repository.insertIfNoOpenItem(inFlight("eng-3", FIRM_A, NOW.plusSeconds(1)));
        repository.insertIfNoOpenItem(done("eng-4", FIRM_A, NOW.minusSeconds(5)));

        assertThat(repository.findExpiredLeases(NOW, 10)).extracting(WorkItem::id)
                .containsExactlyInAnyOrder(expired.id(), expiredEarlier.id());
        assertThat(repository.findExpiredLeases(NOW, 1)).hasSize(1);
    }

    @Test
    void statusCountsByStateAndFirmWithLoadsInFlightAndOldestOutstandingAge() {
        repository.insertIfNoOpenItem(outstanding("eng-1", FIRM_A, NOW.minusSeconds(120)));
        repository.insertIfNoOpenItem(
                item("eng-2", FIRM_B, WorkItemState.OUTSTANDING, NOW.minusSeconds(30), NOW.plusSeconds(60), null));
        repository.insertIfNoOpenItem(inFlight("eng-3", FIRM_A, NOW.plusSeconds(60)));
        repository.insertIfNoOpenItem(done("eng-4", FIRM_B, NOW.plusSeconds(60)));
        repository.insertIfNoOpenItem(parked("eng-5", FIRM_B));

        WorkStatus status = repository.status(NOW);

        assertThat(status.countsByState()).isEqualTo(Map.of(
                WorkItemState.OUTSTANDING, 2L, WorkItemState.IN_FLIGHT, 1L,
                WorkItemState.DONE, 1L, WorkItemState.PARKED, 1L));
        assertThat(status.countsByFirm()).isEqualTo(Map.of(
                FIRM_A, Map.of(WorkItemState.OUTSTANDING, 1L, WorkItemState.IN_FLIGHT, 1L),
                FIRM_B, Map.of(WorkItemState.OUTSTANDING, 1L, WorkItemState.DONE, 1L, WorkItemState.PARKED, 1L)));
        assertThat(status.loadsInFlight()).isEqualTo(2);
        assertThat(status.oldestOutstandingAge()).contains(Duration.ofSeconds(120));
    }

    @Test
    void statusOfAnEmptyListHasNoOldestOutstandingAge() {
        assertThat(repository.status(NOW).oldestOutstandingAge()).isEqualTo(Optional.empty());
        assertThat(repository.status(NOW).loadsInFlight()).isZero();
    }
}
