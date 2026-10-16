package com.caseware.fanout.config;

import com.caseware.fanout.adapter.out.downstream.HttpLoadEngagementAdapter;
import com.caseware.fanout.application.port.out.CapacitySettingsPort;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.application.port.out.LoadEngagementPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.application.service.DispatchCycleService;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.caseware.fanout.domain.policy.FairnessPolicy;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/** Wiring of the fan-out worker. Services and policies are created here only, as beans. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FanoutProperties.class)
@EnableScheduling
public class FanoutConfiguration {

    @Bean
    FairnessPolicy fairnessPolicy() {
        return new FairnessPolicy();
    }

    /** One virtual thread per load; the limit is C, enforced by the dispatch cycle, not by this pool. */
    @Bean
    ExecutorService loadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    RestClient emsRestClient(FanoutProperties properties) {
        return HttpLoadEngagementAdapter.restClient(properties.ems().baseUrl(), properties.load().connectTimeout(),
                properties.load().timeout());
    }

    @Bean
    HttpLoadEngagementAdapter loadEngagementAdapter(RestClient emsRestClient) {
        return new HttpLoadEngagementAdapter(emsRestClient);
    }

    /**
     * The dispatch cycle. capacity_settings is initialised from configuration here, if the row does
     * not exist yet, so no cycle can run before C and F are in the database.
     */
    @Bean
    DispatchCycleService dispatchCycleService(WorkItemRepositoryPort repository, LoadEngagementPort ems,
            CapacitySettingsPort capacitySettings, ClockPort clock, DomainEventPublisherPort events,
            ExecutorService loadExecutor, FairnessPolicy fairnessPolicy, FanoutProperties properties) {
        capacitySettings.initialiseIfAbsent(
                new ConcurrencyLimits(properties.limits().global(), properties.limits().perFirm()));
        FanoutProperties.Retry retry = properties.retry();
        return new DispatchCycleService(repository, ems, capacitySettings, clock, events, loadExecutor,
                fairnessPolicy, properties.load().timeout().plus(properties.load().leaseGrace()),
                new RetryPolicy(retry.maxAttempts(), retry.baseDelay(), retry.maxDelay()));
    }
}
