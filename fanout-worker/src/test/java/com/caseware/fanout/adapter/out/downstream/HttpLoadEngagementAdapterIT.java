package com.caseware.fanout.adapter.out.downstream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.WorkItemId;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The adapter only translates HTTP into a neutral LoadOutcome (contracts/outbound-ports.md). */
class HttpLoadEngagementAdapterIT {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final WorkItemId WORK_ITEM = new WorkItemId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final FirmId FIRM = new FirmId("firm-1");
    private static final EngagementId ENGAGEMENT = new EngagementId("eng-1");
    private static final String LOAD_PATH = "/firms/firm-1/engagements/eng-1/load";

    @RegisterExtension
    static final WireMockExtension EMS = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static HttpLoadEngagementAdapter adapterFor(URI baseUrl) {
        return new HttpLoadEngagementAdapter(HttpLoadEngagementAdapter.restClient(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT));
    }

    private static HttpLoadEngagementAdapter adapter() {
        return adapterFor(URI.create(EMS.baseUrl()));
    }

    private static LoadOutcome load() {
        return adapter().load(WORK_ITEM, FIRM, ENGAGEMENT);
    }

    @Test
    void postsToTheEngagementLoadPathWithTheWorkItemAsIdempotencyKey() {
        EMS.stubFor(post(urlPathMatching("/firms/.*/engagements/.*/load")).willReturn(aResponse().withStatus(204)));

        load();

        EMS.verify(1, postRequestedFor(urlEqualTo(LOAD_PATH))
                .withHeader("Idempotency-Key", equalTo("00000000-0000-0000-0000-00000000000a")));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 400, 404, 410, 429, 500, 503})
    void everyStatusIsReturnedAsAnsweredWithoutClassification(int status) {
        EMS.stubFor(post(urlEqualTo(LOAD_PATH)).willReturn(aResponse().withStatus(status)));

        assertThat(load()).isEqualTo(new LoadOutcome.Answered(status));
    }

    @Test
    void aResponseBodyIsNotKept() {
        EMS.stubFor(post(urlEqualTo(LOAD_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"secret\":\"workpaper content\"}")));

        assertThat(load()).isEqualTo(new LoadOutcome.Answered(200));
    }

    @Test
    void noAnswerBeforeTheReadTimeoutIsTimedOut() {
        EMS.stubFor(post(urlEqualTo(LOAD_PATH)).willReturn(aResponse().withStatus(204).withFixedDelay(1_500)));

        assertThat(load()).isEqualTo(new LoadOutcome.TimedOut());
    }

    @Test
    void aRefusedConnectionIsConnectFailed() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        LoadOutcome outcome = adapterFor(URI.create("http://localhost:" + closedPort)).load(WORK_ITEM, FIRM, ENGAGEMENT);

        assertThat(outcome).isEqualTo(new LoadOutcome.ConnectFailed());
    }

    @Test
    void aResetConnectionIsConnectFailed() {
        EMS.stubFor(post(urlEqualTo(LOAD_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        // WireMock's first fault on a cold JVM can take longer than READ_TIMEOUT; this case is not about timeouts.
        HttpLoadEngagementAdapter adapter = new HttpLoadEngagementAdapter(HttpLoadEngagementAdapter.restClient(
                URI.create(EMS.baseUrl()), CONNECT_TIMEOUT, Duration.ofSeconds(5)));

        assertThat(adapter.load(WORK_ITEM, FIRM, ENGAGEMENT)).isEqualTo(new LoadOutcome.ConnectFailed());
    }
}
