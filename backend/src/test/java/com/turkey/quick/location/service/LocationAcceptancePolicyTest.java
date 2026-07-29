package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LocationAcceptancePolicy")
class LocationAcceptancePolicyTest {

    /** 고정 기준 시각. 시계를 목킹하지 않고 경계값을 다루기 위해 now 를 인자로 넘긴다. */
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    private static RiderLocationUpdateRequest request(Instant measuredAt, String accuracy) {
        return new RiderLocationUpdateRequest(
                new BigDecimal("37.4979"),
                new BigDecimal("127.0276"),
                measuredAt,
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    private static LocationUpdateOutcome evaluate(Instant measuredAt, String accuracy) {
        return LocationAcceptancePolicy.evaluate(request(measuredAt, accuracy), NOW);
    }

    @Nested
    @DisplayName("측정 시각")
    class MeasuredAt {

        @Test
        @DisplayName("현재 시각으로 측정한 위치는 받아들인다")
        void acceptsFreshFix() {
            assertThat(evaluate(NOW, null)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("60초 전 위치는 아직 받아들인다")
        void acceptsFixAtStaleBoundary() {
            // 경계는 포함이다. 여기서 부등호가 뒤집히면 정상 주기의 마지막 전송이 버려진다.
            assertThat(evaluate(NOW.minusSeconds(60), null)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("60초를 넘게 지난 위치는 버린다")
        void discardsStaleFix() {
            assertThat(evaluate(NOW.minusSeconds(61), null)).isEqualTo(LocationUpdateOutcome.STALE);
        }

        @Test
        @DisplayName("5초 이내의 미래는 시계 오차로 보고 받아들인다")
        void toleratesClockSkew() {
            assertThat(evaluate(NOW.plusSeconds(5), null)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("허용 오차를 넘는 미래 시각은 거부한다")
        void rejectsFutureBeyondSkew() {
            // 200 폐기가 아니라 예외다 — GlobalExceptionHandler 가 400 으로 바꾼다.
            // 그대로 저장하면 #82 의 시각 단조성 판정이 막혀 이후 정상 좌표가 전부 버려진다.
            assertThatThrownBy(() -> evaluate(NOW.plusSeconds(6), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("측정 시각");
        }

        @Test
        @DisplayName("아주 먼 미래도 같은 이유로 거부한다")
        void rejectsFarFuture() {
            assertThatThrownBy(() -> evaluate(NOW.plus(Duration.ofDays(1)), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("정확도")
    class Accuracy {

        @Test
        @DisplayName("정확도가 상한과 같으면 받아들인다")
        void acceptsAccuracyAtLimit() {
            assertThat(evaluate(NOW, "100")).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("정확도가 상한을 넘으면 버린다")
        void discardsLowAccuracy() {
            assertThat(evaluate(NOW, "100.01")).isEqualTo(LocationUpdateOutcome.LOW_ACCURACY);
        }

        @Test
        @DisplayName("정확도를 주지 않으면 판정하지 않는다")
        void skipsCheckWhenAccuracyAbsent() {
            // 정확도를 제공하지 않는 단말이 있다. 없다는 것만으로 버리면 그 기기는 아예 못 쓴다.
            assertThat(evaluate(NOW, null)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("정확도 0은 받아들인다")
        void acceptsZeroAccuracy() {
            assertThat(evaluate(NOW, "0")).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("판정 순서")
    class Precedence {

        @Test
        @DisplayName("오래됐고 정확도도 낮으면 오래된 것으로 판정한다")
        void reportsStaleBeforeLowAccuracy() {
            // 사유는 하나만 응답에 담기므로 순서를 고정해 둔다. 어느 쪽이든 결과는 폐기지만,
            // 응답이 오갈 때마다 사유가 달라지면 클라이언트 로그를 신뢰할 수 없다.
            assertThat(evaluate(NOW.minusSeconds(120), "999")).isEqualTo(LocationUpdateOutcome.STALE);
        }

        @Test
        @DisplayName("미래 시각은 정확도보다 먼저 걸린다")
        void checksFutureBeforeAccuracy() {
            assertThatThrownBy(() -> evaluate(NOW.plusSeconds(60), "999"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
