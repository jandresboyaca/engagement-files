package com.caseware.fanout.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.model.FirmId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConcurrencyLimitsTest {

    private static final FirmId FIRM_A = new FirmId("firm-a");
    private static final FirmId FIRM_B = new FirmId("firm-b");

    @Test
    void freeGlobalSlotsAreTheCapMinusLoadsInFlight() {
        InFlightCounts inFlight = new InFlightCounts(7, Map.of(FIRM_A, 4, FIRM_B, 3));

        assertThat(new ConcurrencyLimits(10, 5).freeGlobal(inFlight)).isEqualTo(3);
    }

    @Test
    void freeGlobalSlotsAreNeverNegative() {
        InFlightCounts inFlight = new InFlightCounts(12, Map.of(FIRM_A, 12));

        assertThat(new ConcurrencyLimits(10, 5).freeGlobal(inFlight)).isZero();
    }

    @Test
    void freeSlotsForAFirmAreThePerFirmCapMinusItsLoadsInFlight() {
        InFlightCounts inFlight = new InFlightCounts(7, Map.of(FIRM_A, 4, FIRM_B, 3));
        ConcurrencyLimits limits = new ConcurrencyLimits(10, 5);

        assertThat(limits.freeForFirm(FIRM_A, inFlight)).isEqualTo(1);
        assertThat(limits.freeForFirm(FIRM_B, inFlight)).isEqualTo(2);
        assertThat(limits.freeForFirm(new FirmId("firm-c"), inFlight)).isEqualTo(5);
    }

    @Test
    void freeSlotsForAFirmAreNeverNegative() {
        InFlightCounts inFlight = new InFlightCounts(7, Map.of(FIRM_A, 7));

        assertThat(new ConcurrencyLimits(10, 5).freeForFirm(FIRM_A, inFlight)).isZero();
    }

    @Test
    void negativeLimitsAreRejected() {
        assertThatThrownBy(() -> new ConcurrencyLimits(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConcurrencyLimits(5, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPerFirmLimitAboveTheGlobalLimitIsRejected() {
        assertThatThrownBy(() -> new ConcurrencyLimits(5, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGlobalLimitOfZeroGivesNoFreeSlot() {
        ConcurrencyLimits paused = new ConcurrencyLimits(0, 0);
        InFlightCounts nothing = new InFlightCounts(0, Map.of());

        assertThat(paused.freeGlobal(nothing)).isZero();
        assertThat(paused.freeForFirm(FIRM_A, nothing)).isZero();
    }
}
