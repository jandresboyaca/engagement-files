package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.application.port.out.CapacitySettingsPort;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** C and F in the one-row table {@code capacity_settings}; the operator changes them with SQL. */
@Repository
public class JdbcCapacitySettings implements CapacitySettingsPort {

    private final JdbcClient jdbcClient;

    public JdbcCapacitySettings(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ConcurrencyLimits current() {
        return jdbcClient.sql("select global_limit, per_firm_limit from capacity_settings where id = 1")
                .query((rs, rowNum) -> new ConcurrencyLimits(rs.getInt("global_limit"), rs.getInt("per_firm_limit")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("capacity_settings has not been initialised"));
    }

    @Override
    public void initialiseIfAbsent(ConcurrencyLimits fromConfiguration) {
        jdbcClient.sql("""
                        insert into capacity_settings (id, global_limit, per_firm_limit, updated_at)
                        values (1, ?, ?, now()) on conflict (id) do nothing""")
                .params(fromConfiguration.global(), fromConfiguration.perFirm())
                .update();
    }
}
