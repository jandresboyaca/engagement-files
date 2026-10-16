package com.caseware.fanout.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DataJdbcTest
@Import(JdbcCapacitySettings.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class JdbcCapacitySettingsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcCapacitySettings adapter;

    private JdbcCapacitySettings settings() {
        return adapter;
    }

    @Test
    void initialiseIfAbsentInsertsTheConfiguredLimits() {
        JdbcCapacitySettings settings = settings();

        settings.initialiseIfAbsent(new ConcurrencyLimits(20, 5));

        assertThat(settings.current()).isEqualTo(new ConcurrencyLimits(20, 5));
    }

    @Test
    void initialiseIfAbsentNeverOverwritesAnExistingRow() {
        JdbcCapacitySettings settings = settings();
        settings.initialiseIfAbsent(new ConcurrencyLimits(20, 5));

        settings.initialiseIfAbsent(new ConcurrencyLimits(50, 10));

        assertThat(settings.current()).isEqualTo(new ConcurrencyLimits(20, 5));
    }

    @Test
    void currentReadsAnOperatorUpdate() {
        JdbcCapacitySettings settings = settings();
        settings.initialiseIfAbsent(new ConcurrencyLimits(20, 5));

        assertThatCode(() -> jdbcClient
                .sql("update capacity_settings set global_limit = 1, per_firm_limit = 1, updated_at = now()")
                .update()).doesNotThrowAnyException();

        assertThat(settings.current()).isEqualTo(new ConcurrencyLimits(1, 1));
    }

    @Test
    void aPerFirmLimitAboveTheGlobalLimitIsRejected() {
        settings().initialiseIfAbsent(new ConcurrencyLimits(20, 5));

        assertThatThrownBy(() -> jdbcClient.sql("update capacity_settings set per_firm_limit = 21").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
