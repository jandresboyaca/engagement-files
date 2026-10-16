package com.caseware.fanout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.SubmitWorkCommand;
import com.caseware.fanout.application.port.in.SubmitWorkUseCase;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Reason;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One engagement from three triggers is one item and one load (US5, SC-004), end to end. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext
class DeduplicationScenarioIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final EngagementId ENGAGEMENT = new EngagementId("eng-dedup");
    private static final FirmId FIRM = new FirmId("firm-a");
    private static final String LOAD = "/firms/firm-a/engagements/eng-dedup/load";
    private static final WireMockServer EMS = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        EMS.start();
        EMS.stubFor(post(urlPathMatching("/firms/[^/]+/engagements/[^/]+/load"))
                .willReturn(aResponse().withStatus(204)));
    }

    @DynamicPropertySource
    static void emsBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("fanout.ems.base-url", EMS::baseUrl);
    }

    @AfterAll
    static void stopEms() {
        EMS.stop();
    }

    @Autowired
    private SubmitWorkUseCase submitWork;

    @Autowired
    private JdbcClient jdbcClient;

    @Value("${fanout.dispatch.period}")
    private Duration period;

    private SubmitResult submit(Reason reason) {
        return submitWork.submit(new SubmitWorkCommand(ENGAGEMENT, FIRM, reason));
    }

    private long rows(String state) {
        return jdbcClient.sql("select count(*) from work_item where engagement_id = ? and state like ?")
                .params(ENGAGEMENT.value(), state)
                .query(Long.class)
                .single();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            pause(Duration.ofMillis(20));
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
    }

    @Test
    void oneEngagementFromThreeTriggersIsOneRowAndOneRequest() {
        SubmitResult activation = submit(Reason.ACTIVATION);
        SubmitResult publish = submit(Reason.PUBLISH);
        SubmitResult sweep = submit(Reason.SWEEP);

        assertThat(activation.created()).isTrue();
        assertThat(publish).isEqualTo(new SubmitResult(activation.workItemId(), false));
        assertThat(sweep).isEqualTo(new SubmitResult(activation.workItemId(), false));
        assertThat(rows("%")).isEqualTo(1);

        await("the item done", () -> rows("DONE") == 1);
        // Further cycles must not load it again.
        pause(period.multipliedBy(10));

        assertThat(rows("%")).isEqualTo(1);
        EMS.verify(1, postRequestedFor(urlPathEqualTo(LOAD)));
    }
}
