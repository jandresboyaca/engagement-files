package com.caseware.fanout.application.port.out;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.WorkStatus;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.policy.FirmBacklog;
import com.caseware.fanout.domain.policy.InFlightCounts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The work list. Each method is its own transaction. See contracts/outbound-ports.md; the whole
 * contract is stated once in the test {@code WorkItemRepositoryContract}.
 */
public interface WorkItemRepositoryPort {

    SubmitResult insertIfNoOpenItem(WorkItem item);

    Optional<WorkItem> findOpenByEngagement(EngagementId engagementId);

    Optional<WorkItem> findById(WorkItemId id);

    /** Items with {@code leaseUntil > now}, whatever their state, in total and per firm. */
    InFlightCounts countInFlight(Instant now);

    /** One entry per firm with due outstanding work. */
    List<FirmBacklog> findFirmsWithDueWork(Instant now);

    /** A firm's due outstanding items, oldest {@code addedAt} first. */
    List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now);

    /** {@code IN_FLIGHT} items whose lease has ended. */
    List<WorkItem> findExpiredLeases(Instant now, int limit);

    /** Writes all fields if the stored version equals {@code expectedVersion}; never inserts. */
    boolean save(WorkItem item, long expectedVersion);

    WorkStatus status(Instant now);
}
