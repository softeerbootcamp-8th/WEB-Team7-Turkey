package com.turkey.quick.location.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RiderLocationUpdateResponse")
class RiderLocationUpdateResponseTest {

    @Test
    @DisplayName("수용한 위치는 갱신됐다고 응답한다")
    void reportsAppliedOnAccept() {
        RiderLocationUpdateResponse response = RiderLocationUpdateResponse.accept(false);

        assertThat(response.applied()).isTrue();
        assertThat(response.published()).isFalse();
        assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("전파까지 했으면 published 가 참이다")
    void reportsPublishedWhenPropagated() {
        // #82 의 "저장은 하되 전파하지 않음"을 표현하려고 두 값을 나눠 뒀다.
        assertThat(RiderLocationUpdateResponse.accept(true).published()).isTrue();
    }

    @Test
    @DisplayName("폐기한 위치는 전파되지 않는다")
    void neverPublishesDiscardedLocation() {
        RiderLocationUpdateResponse response =
                RiderLocationUpdateResponse.discard(LocationUpdateOutcome.STALE);

        assertThat(response.applied()).isFalse();
        assertThat(response.published()).isFalse();
        assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.STALE);
    }

    @Test
    @DisplayName("ACCEPTED 를 폐기 사유로 쓸 수 없다")
    void rejectsAcceptedAsDiscardReason() {
        // applied=false 인데 reason=ACCEPTED 인 응답은 읽는 쪽을 속인다.
        assertThatThrownBy(() -> RiderLocationUpdateResponse.discard(LocationUpdateOutcome.ACCEPTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
