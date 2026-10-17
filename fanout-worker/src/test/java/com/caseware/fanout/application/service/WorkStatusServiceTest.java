package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.model.WorkItemState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkStatusServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final FirmId FIRM_A = new FirmId("firm-a");
    private static final FirmId FIRM_B = new FirmId("firm-b");

    private final FakeWorkItemRepository repository = new FakeWorkItemRepository();
    private final WorkStatusService service = new WorkStatusService(repository, new FakeClock(T0));
    private int engagements;

    private void item(FirmId firm, WorkItemState state, Instant addedAt, Instant leaseUntil) {
        repository.insertIfNoOpenItem(WorkItem.restore(new WorkItem.Snapshot(WorkItemId.newId(),
                new EngagementId("eng-" + ++engagements), firm, Reason.SWEEP, state,
                new Attempt(state == WorkItemState.OUTSTANDING ? 0 : 1), addedAt, addedAt, leaseUntil,
                state == WorkItemState.PARKED ? "HTTP_404" : null, 0L)));
    }

    private void aMixOfItems() {
        item(FIRM_A, WorkItemState.OUTSTANDING, T0.minusSeconds(600), null);
        item(FIRM_A, WorkItemState.OUTSTANDING, T0.minusSeconds(60), null);
        item(FIRM_A, WorkItemState.IN_FLIGHT, T0.minusSeconds(900), T0.plusSeconds(60));
        item(FIRM_A, WorkItemState.DONE, T0.minusSeconds(900), T0.plusSeconds(30)); // marked done during its load
        item(FIRM_A, WorkItemState.PARKED, T0.minusSeconds(900), null);
        item(FIRM_B, WorkItemState.DONE, T0.minusSeconds(900), null);
        item(FIRM_B, WorkItemState.IN_FLIGHT, T0.minusSeconds(900), T0.minusSeconds(1)); // lease ended
    }

    @Test
    void countsPerStateIncludeParkedItems() {
        aMixOfItems();

        WorkStatus status = service.status();

        assertThat(status.countsByState()).isEqualTo(Map.of(
                WorkItemState.OUTSTANDING, 2L, WorkItemState.IN_FLIGHT, 2L, WorkItemState.DONE, 2L,
                WorkItemState.PARKED, 1L));
    }

    @Test
    void countsPerFirm() {
        aMixOfItems();

        WorkStatus status = service.status();

        assertThat(status.countsByFirm()).isEqualTo(Map.of(
                FIRM_A, Map.of(WorkItemState.OUTSTANDING, 2L, WorkItemState.IN_FLIGHT, 1L, WorkItemState.DONE, 1L,
                        WorkItemState.PARKED, 1L),
                FIRM_B, Map.of(WorkItemState.DONE, 1L, WorkItemState.IN_FLIGHT, 1L)));
    }

    @Test
    void loadsInFlightCountUnexpiredLeasesInAnyState() {
        aMixOfItems();

        WorkStatus status = service.status();

        assertThat(status.loadsInFlight()).isEqualTo(2);
    }

    @Test
    void oldestOutstandingAgeIsMeasuredFromTheOldestOutstandingItem() {
        aMixOfItems();

        WorkStatus status = service.status();

        assertThat(status.oldestOutstandingAge()).contains(Duration.ofMinutes(10));
    }

    @Test
    void oldestOutstandingAgeIsEmptyWithNothingOutstanding() {
        item(FIRM_A, WorkItemState.DONE, T0.minusSeconds(900), null);

        WorkStatus status = service.status();

        assertThat(status.oldestOutstandingAge()).isEmpty();
        assertThat(status.countsByState()).isEqualTo(Map.of(WorkItemState.DONE, 1L));
    }
}
