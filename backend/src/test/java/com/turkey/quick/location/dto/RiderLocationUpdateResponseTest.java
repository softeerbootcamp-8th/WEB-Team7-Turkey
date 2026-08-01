package com.turkey.quick.location.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RiderLocationUpdateResponse")
class RiderLocationUpdateResponseTest {

    @Test
    @DisplayName("정상 갱신은 저장됐다고 응답한다")
    void reportsAppliedOnAccepted() {
        RiderLocationUpdateResponse response =
                RiderLocationUpdateResponse.of(LocationUpdateOutcome.ACCEPTED, false);

        assertThat(response.applied()).isTrue();
        assertThat(response.published()).isFalse();
        assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("전파까지 했으면 published 가 참이다")
    void reportsPublishedWhenPropagated() {
        assertThat(RiderLocationUpdateResponse.of(LocationUpdateOutcome.ACCEPTED, true).published())
                .isTrue();
    }

    @Test
    @DisplayName("중복은 저장됐지만 전파되지 않았다고 응답한다")
    void reportsStoredButNotPublishedOnDuplicate() {
        // #82 의 핵심 상태다. applied 와 published 를 나눠 둔 이유가 이것이다.
        RiderLocationUpdateResponse response =
                RiderLocationUpdateResponse.of(LocationUpdateOutcome.DUPLICATE, false);

        assertThat(response.applied()).isTrue();
        assertThat(response.published()).isFalse();
        assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.DUPLICATE);
    }

    @Test
    @DisplayName("폐기한 판정은 저장되지 않았다고 응답한다")
    void reportsNotAppliedOnDiscardedOutcomes() {
        for (var outcome : new LocationUpdateOutcome[]{
                LocationUpdateOutcome.STALE,
                LocationUpdateOutcome.LOW_ACCURACY,
                LocationUpdateOutcome.NON_MONOTONIC,
                LocationUpdateOutcome.IMPLAUSIBLE_SPEED}) {
            RiderLocationUpdateResponse response = RiderLocationUpdateResponse.of(outcome, false);

            assertThat(response.applied()).as("%s", outcome).isFalse();
            assertThat(response.published()).as("%s", outcome).isFalse();
        }
    }

    @Test
    @DisplayName("전송 대상이 아닌 판정을 전송했다고 응답할 수 없다")
    void rejectsPublishedFlagOnNonPublishableOutcome() {
        // applied·published 를 호출자가 직접 정하게 두면 reason 과 어긋난 조합이 만들어진다.
        assertThatThrownBy(() ->
                RiderLocationUpdateResponse.of(LocationUpdateOutcome.DUPLICATE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
