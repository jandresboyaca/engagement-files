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
 * US1 cases: slots go to firms in order of their oldest due item, with equal loads in flight.
 * US2 cases: fewest loads in flight first, counting slots given in this cycle; ties to the oldest
 * due item (research.md section 3).
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

    // US2: fewest loads in flight first, counting slots given in this cycle; ties to the oldest due item.

    @Test
    void aSlotGoesToTheFirmWithTheFewestLoadsInFlight() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0), new FirmBacklog(FIRM_B, T0.plusSeconds(10)));
        InFlightCounts inFlight = new InFlightCounts(4, Map.of(FIRM_A, 3, FIRM_B, 1));

        assertThat(policy.allocate(new ConcurrencyLimits(5, 5), inFlight, backlog)).isEqualTo(Map.of(FIRM_B, 1));
    }

    @Test
    void slotsGivenInThisCycleCountAsLoadsInFlight() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0), new FirmBacklog(FIRM_B, T0.plusSeconds(10)));
        InFlightCounts fourFree = new InFlightCounts(1, Map.of(FIRM_C, 1));

        assertThat(policy.allocate(new ConcurrencyLimits(5, 5), fourFree, backlog))
                .isEqualTo(Map.of(FIRM_A, 2, FIRM_B, 2));
    }

    @Test
    void whenLoadsInFlightAreEqualTheOldestDueItemWins() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0.plusSeconds(10)), new FirmBacklog(FIRM_B, T0));
        InFlightCounts twoFree = new InFlightCounts(3, Map.of(FIRM_B, 1, FIRM_C, 2));

        assertThat(policy.allocate(new ConcurrencyLimits(5, 5), twoFree, backlog))
                .isEqualTo(Map.of(FIRM_A, 1, FIRM_B, 1));
    }

    // US2 scenario 2
    @Test
    void aFirmWithNothingInFlightBeatsAnOlderFirmNearOrAtItsCap() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0), new FirmBacklog(FIRM_B, T0.plusSeconds(60)));

        InFlightCounts nearCap = new InFlightCounts(4, Map.of(FIRM_A, 4));
        assertThat(policy.allocate(new ConcurrencyLimits(5, 5), nearCap, backlog)).isEqualTo(Map.of(FIRM_B, 1));

        InFlightCounts atCap = new InFlightCounts(5, Map.of(FIRM_A, 5));
        assertThat(policy.allocate(new ConcurrencyLimits(6, 5), atCap, backlog)).isEqualTo(Map.of(FIRM_B, 1));
    }

    // US2 scenario 1
    @Test
    void neverMoreThanFPerFirmEvenWithFreeGlobalSlots() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0), new FirmBacklog(FIRM_B, T0.plusSeconds(1)));

        assertThat(policy.allocate(new ConcurrencyLimits(10, 3), NOTHING_IN_FLIGHT, backlog))
                .isEqualTo(Map.of(FIRM_A, 3, FIRM_B, 3));
    }

    // US2 scenario 3
    @Test
    void aSingleFirmIsStillCappedAtF() {
        List<FirmBacklog> backlog = List.of(new FirmBacklog(FIRM_A, T0));

        assertThat(policy.allocate(new ConcurrencyLimits(20, 5), NOTHING_IN_FLIGHT, backlog))
                .isEqualTo(Map.of(FIRM_A, 5));
    }

    @Test
    void theAllocationNeverSeesTheItemsReason() {
        assertThat(Arrays.stream(FirmBacklog.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("firmId", "oldestDueAddedAt");
    }
}
