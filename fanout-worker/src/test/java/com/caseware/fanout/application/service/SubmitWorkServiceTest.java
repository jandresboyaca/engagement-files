package com.caseware.fanout.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.application.dto.SubmitResult;
import com.caseware.fanout.application.dto.SubmitWorkCommand;
import com.caseware.fanout.application.fake.FakeCapacitySettings;
import com.caseware.fanout.application.fake.FakeClock;
import com.caseware.fanout.application.fake.FakeLoadEngagement;
import com.caseware.fanout.application.fake.FakeWorkItemRepository;
import com.caseware.fanout.application.fake.ManualExecutor;
import com.caseware.fanout.application.fake.RecordingEventPublisher;
import com.caseware.fanout.domain.model.Attempt;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.Reason;
import com.caseware.fanout.domain.model.WorkItem;
import com.caseware.fanout.domain.model.WorkItemState;
import com.caseware.fanout.domain.policy.ConcurrencyLimits;
import com.caseware.fanout.domain.policy.FairnessPolicy;
import com.caseware.fanout.domain.policy.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubmitWorkServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final EngagementId ENGAGEMENT = new EngagementId("eng-1");
    private static final FirmId FIRM = new FirmId("firm-a");

    private final FakeWorkItemRepository repository = new FakeWorkItemRepository();
    private final FakeClock clock = new FakeClock(T0);
    private final FakeLoadEngagement ems = new FakeLoadEngagement();
    private final ManualExecutor executor = new ManualExecutor();
    private final SubmitWorkService service = new SubmitWorkService(repository, clock);
    private final DispatchCycleService cycle = new DispatchCycleService(repository, ems,
            new FakeCapacitySettings(new ConcurrencyLimits(10, 10)), clock, new RecordingEventPublisher(), executor,
            new FairnessPolicy(), LEASE, new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30)));

    private SubmitResult submit(Reason reason) {
        return service.submit(new SubmitWorkCommand(ENGAGEMENT, FIRM, reason));
    }

    /** Runs cycles and answers every load they start, as time goes by. */
    private void dispatch(int cycles) {
        for (int i = 0; i < cycles; i++) {
            cycle.runOnce();
            executor.runAll();
            clock.advance(Duration.ofSeconds(1));
        }
    }

    private long items() {
        return repository.status(clock.now()).countsByState().values().stream().mapToLong(Long::longValue).sum();
    }

    private WorkItemState state(SubmitResult result) {
        return repository.findById(result.workItemId()).map(WorkItem::state).orElse(null);
    }

    @Test
    void submitCreatesAnOutstandingItemDueNow() {
        SubmitResult result = submit(Reason.PUBLISH);

        assertThat(result.created()).isTrue();
        WorkItem item = repository.findById(result.workItemId()).orElseThrow();
        assertThat(item.engagementId()).isEqualTo(ENGAGEMENT);
        assertThat(item.firmId()).isEqualTo(FIRM);
        assertThat(item.reason()).isEqualTo(Reason.PUBLISH);
        assertThat(item.state()).isEqualTo(WorkItemState.OUTSTANDING);
        assertThat(item.attempt()).isEqualTo(Attempt.none());
        assertThat(item.nextAttemptAt()).isEqualTo(T0);
    }

    @Test
    void addingAnOutstandingEngagementAgainReturnsTheSameItem() {
        SubmitResult first = submit(Reason.PUBLISH);

        SubmitResult second = submit(Reason.SWEEP);

        assertThat(second).isEqualTo(new SubmitResult(first.workItemId(), false));
        assertThat(items()).isEqualTo(1);
    }

    @Test
    void addingAnEngagementInFlightAgainReturnsTheSameItem() {
        SubmitResult first = submit(Reason.PUBLISH);
        cycle.runOnce();
        assertThat(state(first)).isEqualTo(WorkItemState.IN_FLIGHT);

        SubmitResult second = submit(Reason.ACTIVATION);

        assertThat(second).isEqualTo(new SubmitResult(first.workItemId(), false));
        assertThat(items()).isEqualTo(1);
    }

    @Test
    void theSameEngagementFromThreeTriggersIsOneItemLoadedOnce() {
        SubmitResult activation = submit(Reason.ACTIVATION);
        SubmitResult publish = submit(Reason.PUBLISH);
        SubmitResult sweep = submit(Reason.SWEEP);

        dispatch(5);

        assertThat(publish.workItemId()).isEqualTo(activation.workItemId());
        assertThat(sweep.workItemId()).isEqualTo(activation.workItemId());
        assertThat(items()).isEqualTo(1);
        assertThat(state(activation)).isEqualTo(WorkItemState.DONE);
        assertThat(ems.callsFor(ENGAGEMENT)).isEqualTo(1);
    }

    @Test
    void afterDoneAddingTheEngagementAgainCreatesANewItemThatIsLoadedAgain() {
        SubmitResult first = submit(Reason.PUBLISH);
        dispatch(2);
        assertThat(state(first)).isEqualTo(WorkItemState.DONE);

        SubmitResult again = submit(Reason.PUBLISH);
        dispatch(2);

        assertThat(again.created()).isTrue();
        assertThat(again.workItemId()).isNotEqualTo(first.workItemId());
        assertThat(state(again)).isEqualTo(WorkItemState.DONE);
        assertThat(ems.callsFor(ENGAGEMENT)).isEqualTo(2);
    }

    @Test
    void afterParkedAddingTheEngagementAgainCreatesANewItem() {
        ems.script(ENGAGEMENT, new LoadOutcome.Answered(404));
        SubmitResult first = submit(Reason.PUBLISH);
        dispatch(2);
        assertThat(state(first)).isEqualTo(WorkItemState.PARKED);

        SubmitResult again = submit(Reason.SWEEP);

        assertThat(again.created()).isTrue();
        assertThat(again.workItemId()).isNotEqualTo(first.workItemId());
        assertThat(state(again)).isEqualTo(WorkItemState.OUTSTANDING);
    }

    @Test
    void invalidIdsAreRejectedBeforeTheServiceIsCalled() {
        assertThatThrownBy(() -> new EngagementId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FirmId("f".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(items()).isZero();
    }
}
