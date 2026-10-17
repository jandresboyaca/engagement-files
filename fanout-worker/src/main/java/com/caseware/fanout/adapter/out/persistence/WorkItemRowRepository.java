package com.caseware.fanout.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Spring Data JDBC repository for {@code work_item}. Only the aggregations are written as SQL. */
interface WorkItemRowRepository extends CrudRepository<WorkItemRow, UUID> {

    Optional<WorkItemRow> findFirstByEngagementIdAndStateIn(String engagementId, Collection<String> states);

    List<WorkItemRow> findByFirmIdAndStateAndNextAttemptAtLessThanEqualOrderByAddedAtAsc(String firmId, String state,
            Instant now, Limit limit);

    List<WorkItemRow> findByStateAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(String state, Instant now, Limit limit);

    /** Loads in flight per firm: unexpired leases, whatever the state. */
    @Query("select firm_id, count(*) as loads from work_item where lease_until > :now group by firm_id")
    List<FirmLoads> countInFlightPerFirm(Instant now);

    @Query("""
            select firm_id, min(added_at) as oldest_due_added_at from work_item
            where state = 'OUTSTANDING' and next_attempt_at <= :now group by firm_id""")
    List<FirmOldestDue> findFirmsWithDueWork(Instant now);

    @Query("select firm_id, state, count(*) as items from work_item group by firm_id, state")
    List<FirmStateCount> countPerFirmAndState();

    @Query("select min(added_at) from work_item where state = 'OUTSTANDING'")
    Optional<Instant> findOldestOutstandingAddedAt();

    record FirmLoads(String firmId, long loads) {
    }

    record FirmOldestDue(String firmId, Instant oldestDueAddedAt) {
    }

    record FirmStateCount(String firmId, String state, long items) {
    }
}
