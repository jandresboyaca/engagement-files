package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.FirmId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands out free slots to firms with due work (research.md section 3). First version: firms in
 * order of their oldest due item, each within its own free slots.
 */
public final class FairnessPolicy {

    public Map<FirmId, Integer> allocate(ConcurrencyLimits limits, InFlightCounts inFlight, List<FirmBacklog> backlog) {
        Map<FirmId, Integer> slots = new LinkedHashMap<>();
        int remaining = limits.freeGlobal(inFlight);
        List<FirmBacklog> oldestFirst = backlog.stream()
                .sorted(Comparator.comparing(FirmBacklog::oldestDueAddedAt))
                .toList();
        for (FirmBacklog firm : oldestFirst) {
            if (remaining == 0) {
                break;
            }
            int given = Math.min(remaining, limits.freeForFirm(firm.firmId(), inFlight));
            if (given > 0) {
                slots.put(firm.firmId(), given);
                remaining -= given;
            }
        }
        return slots;
    }
}
