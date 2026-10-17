package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.FirmId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hands out free slots to firms with due work (research.md section 3): one slot at a time, always
 * to the firm with the fewest loads in flight, counting slots given in this cycle; a firm at F is
 * skipped; ties go to the firm served least recently, then to the firm whose oldest due item has
 * waited longest. A firm not served yet ranks ahead of every firm already served, and while N
 * firms have due work each one is served at least once every ⌈N / C⌉ rounds, however large the
 * other firms' backlogs are.
 *
 * <p>When each firm was last served is kept in memory and starts empty after a restart. Not
 * thread-safe: one dispatch cycle at a time.
 */
public final class FairnessPolicy {

    private static final long NEVER_SERVED = 0L;

    private final Map<FirmId, Long> lastServed = new HashMap<>();
    private long slotsGiven;

    public Map<FirmId, Integer> allocate(ConcurrencyLimits limits, InFlightCounts inFlight, List<FirmBacklog> backlog) {
        Map<FirmId, Integer> given = new LinkedHashMap<>();
        Comparator<FirmBacklog> nextInLine = Comparator
                .comparingInt((FirmBacklog firm) -> loads(firm, inFlight, given))
                .thenComparingLong(firm -> lastServed.getOrDefault(firm.firmId(), NEVER_SERVED))
                .thenComparing(FirmBacklog::oldestDueAddedAt)
                .thenComparing(firm -> firm.firmId().value());
        for (int remaining = limits.freeGlobal(inFlight); remaining > 0; remaining--) {
            Optional<FirmBacklog> next = backlog.stream()
                    .filter(firm -> given.getOrDefault(firm.firmId(), 0) < limits.freeForFirm(firm.firmId(), inFlight))
                    .min(nextInLine);
            if (next.isEmpty()) {
                break;
            }
            given.merge(next.get().firmId(), 1, Integer::sum);
            lastServed.put(next.get().firmId(), ++slotsGiven);
        }
        return given;
    }

    private static int loads(FirmBacklog firm, InFlightCounts inFlight, Map<FirmId, Integer> given) {
        return inFlight.perFirm().getOrDefault(firm.firmId(), 0) + given.getOrDefault(firm.firmId(), 0);
    }
}
