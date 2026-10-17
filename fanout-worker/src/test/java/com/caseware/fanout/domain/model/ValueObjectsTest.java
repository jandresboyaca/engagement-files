package com.caseware.fanout.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectsTest {

    @Test
    void workItemIdRejectsNull() {
        assertThatThrownBy(() -> new WorkItemId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newWorkItemIdsAreDistinct() {
        assertThat(WorkItemId.newId()).isNotEqualTo(WorkItemId.newId());
    }

    @Test
    void firmIdRejectsNull() {
        assertThatThrownBy(() -> new FirmId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void firmIdRejectsBlank(String value) {
        assertThatThrownBy(() -> new FirmId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firmIdAcceptsSixtyFourCharsAndRejectsSixtyFive() {
        assertThat(new FirmId("f".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> new FirmId("f".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void engagementIdRejectsNull() {
        assertThatThrownBy(() -> new EngagementId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void engagementIdRejectsBlank(String value) {
        assertThatThrownBy(() -> new EngagementId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void engagementIdAcceptsSixtyFourCharsAndRejectsSixtyFive() {
        assertThat(new EngagementId("e".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> new EngagementId("e".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptRejectsNegativeCount() {
        assertThatThrownBy(() -> new Attempt(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptNextAddsOne() {
        assertThat(Attempt.none().count()).isZero();
        assertThat(new Attempt(2).next()).isEqualTo(new Attempt(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 299})
    void answeredWithTwoHundredStatusIsSuccess(int status) {
        assertThat(new LoadOutcome.Answered(status).isSuccess()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 300, 404, 429, 503})
    void answeredOutsideTwoHundredIsNotSuccess(int status) {
        assertThat(new LoadOutcome.Answered(status).isSuccess()).isFalse();
    }

    @Test
    void timeoutAndConnectFailureAreNotSuccess() {
        assertThat(new LoadOutcome.TimedOut().isSuccess()).isFalse();
        assertThat(new LoadOutcome.ConnectFailed().isSuccess()).isFalse();
    }

    @Test
    void errorCodesNameTheNeutralResult() {
        assertThat(new LoadOutcome.Answered(503).errorCode()).isEqualTo("HTTP_503");
        assertThat(new LoadOutcome.TimedOut().errorCode()).isEqualTo("TIMEOUT");
        assertThat(new LoadOutcome.ConnectFailed().errorCode()).isEqualTo("CONNECT");
    }

    @Test
    void workItemIdWrapsAUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(new WorkItemId(uuid).value()).isEqualTo(uuid);
    }
}
