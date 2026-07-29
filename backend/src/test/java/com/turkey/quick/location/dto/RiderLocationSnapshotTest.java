package com.turkey.quick.location.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RiderLocationSnapshot")
class RiderLocationSnapshotTest {

    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 7, 29, 12, 34, 56);

    private static RiderLocationSnapshot snapshot(String latitude, String longitude, String accuracy) {
        return new RiderLocationSnapshot(
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                MEASURED_AT,
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    @Nested
    @DisplayName("자릿수 정규화")
    class ScaleNormalization {

        @Test
        @DisplayName("좌표는 소수점 7자리로 맞춰진다")
        void scalesCoordinatesToSevenDecimals() {
            // rider_location_history 의 DECIMAL(10,7) 과 같은 자릿수여야 한다. 맞추지 않으면
            // 이 값이 이력으로 저장될 때(#102) DB 가 반올림해 메모리 값과 저장 값이 달라진다.
            RiderLocationSnapshot location = snapshot("37.4979", "127.0276", null);

            assertThat(location.latitude()).isEqualTo(new BigDecimal("37.4979000"));
            assertThat(location.longitude()).isEqualTo(new BigDecimal("127.0276000"));
        }

        @Test
        @DisplayName("자릿수가 더 많은 좌표는 반올림된다")
        void roundsCoordinatesWithExcessPrecision() {
            RiderLocationSnapshot location = snapshot("37.49790005", "127.02760004", null);

            assertThat(location.latitude()).isEqualTo(new BigDecimal("37.4979001"));
            assertThat(location.longitude()).isEqualTo(new BigDecimal("127.0276000"));
        }

        @Test
        @DisplayName("정확도는 소수점 2자리로 맞춰진다")
        void scalesAccuracyToTwoDecimals() {
            RiderLocationSnapshot location = snapshot("37.4979", "127.0276", "12.5");

            assertThat(location.accuracyMeters()).isEqualTo(new BigDecimal("12.50"));
        }

        @Test
        @DisplayName("정확도는 없을 수 있다")
        void allowsMissingAccuracy() {
            // 단말·환경에 따라 정확도를 주지 않는다. 엔터티 컬럼도 nullable 이다.
            assertThat(snapshot("37.4979", "127.0276", null).accuracyMeters()).isNull();
        }
    }

    @Nested
    @DisplayName("필수 값 검증")
    class RequiredValues {

        @Test
        @DisplayName("위도가 없으면 예외다")
        void rejectsNullLatitude() {
            assertThatThrownBy(() -> new RiderLocationSnapshot(null, BigDecimal.ONE, MEASURED_AT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("위도");
        }

        @Test
        @DisplayName("경도가 없으면 예외다")
        void rejectsNullLongitude() {
            assertThatThrownBy(() -> new RiderLocationSnapshot(BigDecimal.ONE, null, MEASURED_AT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("경도");
        }

        @Test
        @DisplayName("측정 시각이 없으면 예외다")
        void rejectsNullMeasuredAt() {
            assertThatThrownBy(() -> new RiderLocationSnapshot(BigDecimal.ONE, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("측정 시각");
        }

        @Test
        @DisplayName("범위를 벗어난 좌표는 이 단계에서 거부하지 않는다")
        void doesNotValidateCoordinateRange() {
            // 범위 검증은 요청 검증(#234)의 책임이다. 값 객체가 여기서 함께 거부하면 같은 규칙이
            // 두 곳에 생기고, 나중에 한쪽만 바뀐다.
            assertThat(snapshot("91", "181", null).latitude()).isEqualTo(new BigDecimal("91.0000000"));
        }
    }
}
