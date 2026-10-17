package com.caseware.fanout.application.dto;

import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItemState;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public record WorkStatus(
        Map<WorkItemState, Long> countsByState,
        Map<FirmId, Map<WorkItemState, Long>> countsByFirm,
        int loadsInFlight,
        Optional<Duration> oldestOutstandingAge) {
}
