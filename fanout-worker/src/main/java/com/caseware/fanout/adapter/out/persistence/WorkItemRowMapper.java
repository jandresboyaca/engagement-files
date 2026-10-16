package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import java.time.Instant;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps a row to and from the aggregate's snapshot; value objects are unwrapped by the methods below. */
@Mapper
interface WorkItemRowMapper {

    /** A row to insert: no version yet, so Spring Data inserts it. */
    @Mapping(target = "version", ignore = true)
    WorkItemRow toNewRow(WorkItem.Snapshot snapshot, Instant updatedAt);

    /** A row to update, conditional on {@code expectedVersion}. */
    @Mapping(target = "version", source = "expectedVersion")
    WorkItemRow toRow(WorkItem.Snapshot snapshot, Long expectedVersion, Instant updatedAt);

    WorkItem.Snapshot toSnapshot(WorkItemRow row);

    default UUID fromWorkItemId(WorkItemId id) {
        return id.value();
    }

    default WorkItemId toWorkItemId(UUID value) {
        return new WorkItemId(value);
    }

    default String fromEngagementId(EngagementId id) {
        return id.value();
    }

    default EngagementId toEngagementId(String value) {
        return new EngagementId(value);
    }

    default String fromFirmId(FirmId id) {
        return id.value();
    }

    default FirmId toFirmId(String value) {
        return new FirmId(value);
    }

    default int fromAttempt(Attempt attempt) {
        return attempt.count();
    }

    default Attempt toAttempt(int count) {
        return new Attempt(count);
    }
}
