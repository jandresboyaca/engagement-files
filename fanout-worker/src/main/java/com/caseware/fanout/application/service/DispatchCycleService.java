package com.caseware.fanout.application.service;

import com.caseware.fanout.application.dto.DispatchResult;
import com.caseware.fanout.application.port.in.RunDispatchCycleUseCase;
import com.caseware.fanout.application.port.out.CapacitySettingsPort;
import com.caseware.fanout.application.port.out.ClockPort;
import com.caseware.fanout.application.port.out.DomainEventPublisherPort;
import com.caseware.fanout.application.port.out.LoadEngagementPort;
import com.caseware.fanout.application.port.out.WorkItemRepositoryPort;
import com.caseware.fanout.domain.event.DomainEvent;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemId;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.caseware.fanout.domain.policy.FairnessPolicy;
import com.caseware.fanout.domain.policy.InFlightCounts;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * One dispatch cycle (contracts/inbound-ports.md). Orders the calls only: allocation is
 * {@link FairnessPolicy}, every state change is {@link WorkItem}. Must not run concurrently with
 * itself.
 */
public final class DispatchCycleService implements RunDispatchCycleUseCase {

    private static final Logger LOG = System.getLogger(DispatchCycleService.class.getName());
    private static final int MAX_REAPPLIES = 3;
    private static final int EXPIRY_BATCH = 500;

    private final WorkItemRepositoryPort repository;
    private final LoadEngagementPort ems;
    private final CapacitySettingsPort settings;
    private final ClockPort clock;
    private final DomainEventPublisherPort events;
    private final Executor executor;
    private final FairnessPolicy fairness;
    private final Duration leaseDuration;
    private final RetryPolicy retryPolicy;

    public DispatchCycleService(WorkItemRepositoryPort repository, LoadEngagementPort ems,
            CapacitySettingsPort settings, ClockPort clock, DomainEventPublisherPort events, Executor executor,
            FairnessPolicy fairness, Duration leaseDuration, RetryPolicy retryPolicy) {
        this.repository = repository;
        this.ems = ems;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.executor = executor;
        this.fairness = fairness;
        this.leaseDuration = leaseDuration;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public DispatchResult runOnce() {
        Instant now = clock.now();
        int expired = expireLeases(now);
        ConcurrencyLimits limits = settings.current();
        InFlightCounts inFlight = repository.countInFlight(now);
        Map<FirmId, Integer> slots = fairness.allocate(limits, inFlight, repository.findFirmsWithDueWork(now));

        int started = 0;
        int skipped = 0;
        for (Map.Entry<FirmId, Integer> firm : slots.entrySet()) {
            for (WorkItem item : repository.findOldestDue(firm.getKey(), firm.getValue(), now)) {
                long readVersion = item.version();
                item.claim(now, leaseDuration);
                if (!repository.save(item, readVersion)) {
                    skipped++;
                    continue;
                }
                started++;
                startLoad(item);
            }
        }
        return new DispatchResult(expired, started, skipped);
    }

    /** Step 1: loads that did not answer before their lease ended are timed-out attempts. */
    private int expireLeases(Instant now) {
        int expired = 0;
        for (WorkItem item : repository.findExpiredLeases(now, EXPIRY_BATCH)) {
            long readVersion = item.version();
            item.expireLease(now, retryPolicy);
            List<DomainEvent> raised = item.pullEvents();
            if (repository.save(item, readVersion)) {
                expired++;
                raised.forEach(events::publish);
            }
        }
        return expired;
    }

    private void startLoad(WorkItem claimed) {
        WorkItemId id = claimed.id();
        Attempt attempt = claimed.attempt();
        FirmId firm = claimed.firmId();
        executor.execute(() -> recordAnswer(id, attempt, ems.load(id, firm, claimed.engagementId())));
    }

    private void recordAnswer(WorkItemId id, Attempt attempt, LoadOutcome outcome) {
        for (int apply = 0; apply <= MAX_REAPPLIES; apply++) {
            Optional<WorkItem> current = repository.findById(id);
            if (current.isEmpty()) {
                return;
            }
            WorkItem item = current.get();
            long readVersion = item.version();
            item.answer(attempt, outcome, clock.now(), retryPolicy);
            List<DomainEvent> raised = item.pullEvents();
            if (repository.save(item, readVersion)) {
                raised.forEach(events::publish);
                return;
            }
        }
        LOG.log(Level.WARNING, "answer for workItemId={0} attempt={1} not saved after {2} conflicts; its lease will expire",
                id.value(), attempt.count(), MAX_REAPPLIES + 1);
    }
}
