package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.model.FirmId;
import java.util.Map;

/** Loads in flight (unexpired leases, any state), in total and per firm. */
public record InFlightCounts(int total, Map<FirmId, Integer> perFirm) {
}
