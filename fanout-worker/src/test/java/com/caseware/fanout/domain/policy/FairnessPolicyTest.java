package com.caseware.fanout.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.FirmId;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * First version (US1): slots go to firms in order of their oldest due item. Every case has firms
 * with equal loads in flight, so it stays valid when US2 adds the fewest-in-flight rule.
 */
class FairnessPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final FirmId FIRM_A = new FirmId("firm-a");
    private static final FirmId FIRM_B = new FirmId("firm-b");
    private static final FirmId FIRM_C = new FirmId("firm-c");
    private static final InFlightCounts NOTHING_IN_FLIGHT = new InFlightCounts(0, Map.of());

    private final FairnessPolicy policy = new FairnessPolicy();

    @Test
    void aSingleFreeSlotGoesToTheFirmWithTheOldestDueItem() {
        List<FirmBacklog> backlog = List.of(
                new FirmBacklog(FIRM_A, T0.plusSeconds(20)),
                new FirmBacklog(FIRM_B, T0),
                new FirmBacklog(FIRM_C, T0.plusSeconds(10)));
        InFlightCounts inFlight = new InFlightCounts(9, Map.of(FIRM_A, 3, FIRM_B, 3, FIRM_C, 3));

        assertThat(policy.allocate(new ConcurrencyLimits(10, 5), inFlight, backlog))
                .isEqualTo(Map.of(FIRM_B, 1));
    }

    @Test
    void withEnoughGlobalSlotsEachFirmGetsItsOwnFreeSlots() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0), new FirmBacklog(FIRM_B, T0.plusSeconds(1)));
        InFlightCounts inFlight = new InFlightCounts(2, Map.of(FIRM_A, 1, FIRM_B, 1));

        assertThat(policy.allocate(new ConcurrencyLimits(20, 4), inFlight, backlog))
                .isEqualTo(Map.of(FIRM_A, 3, FIRM_B, 3));
    }

    @Test
    void neverMoreThanTheFreeGlobalSlotsInTotal() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0));

        assertThat(policy.allocate(new ConcurrencyLimits(3, 3), NOTHING_IN_FLIGHT, backlog))
                .isEqualTo(Map.of(FIRM_A, 3));
        assertThat(policy.allocate(new ConcurrencyLimits(5, 5), new InFlightCounts(3, Map.of(FIRM_B, 3)), backlog))
                .isEqualTo(Map.of(FIRM_A, 2));
    }

    @Test
    void neverMoreThanAFirmsOwnFreeSlots() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0));
        InFlightCounts inFlight = new InFlightCounts(3, Map.of(FIRM_A, 3));

        assertThat(policy.allocate(new ConcurrencyLimits(20, 5), inFlight, backlog)).isEqualTo(Map.of(FIRM_A, 2));
    }

    @Test
    void aFirmAlreadyAtItsCapGetsNothing() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0));
        InFlightCounts inFlight = new InFlightCounts(5, Map.of(FIRM_A, 5));

        assertThat(policy.allocate(new ConcurrencyLimits(20, 5), inFlight, backlog)).isEmpty();
    }

    @Test
    void noFreeGlobalSlotGivesNothing() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0));

        assertThat(policy.allocate(new ConcurrencyLimits(0, 0), NOTHING_IN_FLIGHT, backlog)).isEmpty();
    }

    @Test
    void theAllocationNeverSeesTheItemsReason() {
        assertThat(Arrays.stream(FirmBacklog.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("firmId", "oldestDueAddedAt");
    }
}
