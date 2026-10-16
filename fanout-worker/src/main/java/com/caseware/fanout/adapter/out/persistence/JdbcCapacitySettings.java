package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.application.port.out.CapacitySettingsPort;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** C and F in the one-row table {@code capacity_settings}; the operator changes them with SQL. */
@Component
@RequiredArgsConstructor
public class JdbcCapacitySettings implements CapacitySettingsPort {

    private final CapacitySettingsRowRepository settings;

    @Override
    public ConcurrencyLimits current() {
        return settings.findById(CapacitySettingsRow.ID)
                .map(row -> new ConcurrencyLimits(row.globalLimit(), row.perFirmLimit()))
                .orElseThrow(() -> new IllegalStateException("capacity_settings has not been initialised"));
    }

    @Override
    public void initialiseIfAbsent(ConcurrencyLimits fromConfiguration) {
        if (settings.existsById(CapacitySettingsRow.ID)) {
            return;
        }
        try {
            settings.save(CapacitySettingsRow.newRow(fromConfiguration.global(), fromConfiguration.perFirm(),
                    Instant.now()));
        } catch (DuplicateKeyException insertedConcurrently) {
            // Another process inserted it first; its values stand.
        }
    }
}
