package com.caseware.fanout.adapter.out.persistence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The one row of {@code capacity_settings}. Its id is assigned (always 1), so it says itself
 * whether it is new: Spring Data inserts a new row and updates a loaded one.
 */
@Table("capacity_settings")
class CapacitySettingsRow implements Persistable<Short> {

    static final short ID = 1;

    @Id
    private final Short id;
    private final int globalLimit;
    private final int perFirmLimit;
    private final Instant updatedAt;
    @Transient
    private final boolean isNew;

    @PersistenceCreator
    CapacitySettingsRow(Short id, int globalLimit, int perFirmLimit, Instant updatedAt) {
        this(id, globalLimit, perFirmLimit, updatedAt, false);
    }

    private CapacitySettingsRow(Short id, int globalLimit, int perFirmLimit, Instant updatedAt, boolean isNew) {
        this.id = id;
        this.globalLimit = globalLimit;
        this.perFirmLimit = perFirmLimit;
        this.updatedAt = updatedAt;
        this.isNew = isNew;
    }

    static CapacitySettingsRow newRow(int globalLimit, int perFirmLimit, Instant updatedAt) {
        return new CapacitySettingsRow(ID, globalLimit, perFirmLimit, updatedAt, true);
    }

    @Override
    public Short getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    int globalLimit() {
        return globalLimit;
    }

    int perFirmLimit() {
        return perFirmLimit;
    }
}
