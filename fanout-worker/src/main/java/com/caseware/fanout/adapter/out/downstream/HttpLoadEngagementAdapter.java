package com.caseware.fanout.adapter.out.downstream;

import com.caseware.fanout.application.port.out.LoadEngagementPort;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.WorkItemId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the EMS load endpoint (contracts/ems-load-api.yaml, an assumption) and translates the
 * result into a neutral {@link LoadOutcome}. Decides nothing; never throws for an answer or a
 * network error; never reads the response body.
 */
@RequiredArgsConstructor
public class HttpLoadEngagementAdapter implements LoadEngagementPort {

    private final RestClient restClient;

    /** The client this adapter expects: HTTP/1.1 on the JDK client, with connect and read timeouts. */
    public static RestClient restClient(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requestFactory).build();
    }

    @Override
    public LoadOutcome load(WorkItemId workItemId, FirmId firmId, EngagementId engagementId) {
        try {
            int status = restClient.post()
                    .uri("/firms/{firmId}/engagements/{engagementId}/load", firmId.value(), engagementId.value())
                    .header("Idempotency-Key", workItemId.value().toString())
                    .retrieve()
                    .onStatus(any -> true, (request, response) -> { })
                    .toBodilessEntity()
                    .getStatusCode()
                    .value();
            return new LoadOutcome.Answered(status);
        } catch (RestClientException e) {
            return networkFailure(e);
        }
    }

    private static LoadOutcome networkFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return new LoadOutcome.ConnectFailed();
            }
            if (cause instanceof HttpTimeoutException) {
                return new LoadOutcome.TimedOut();
            }
        }
        return new LoadOutcome.ConnectFailed();
    }
}
