package com.turkey.quick.location.service;

import static com.turkey.quick.location.service.RedisRiderLocationStore.decode;
import static com.turkey.quick.location.service.RedisRiderLocationStore.encode;
import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * StringRedisTemplate 을 목킹하지 않는다. 이 클래스에서 실제로 깨지는 것은 Redis 호출이 아니라
 * 값 문자열 형식이고, Redis 왕복은 목으로 검증해도 아무것도 보장하지 못한다.
 */
@DisplayName("RedisRiderLocationStore 값 인코딩")
class RedisRiderLocationStoreTest {

    private static final LocalDateTime MEASURED_AT =
            LocalDateTime.of(2026, 7, 29, 12, 34, 56, 789_000_000);

    private static RiderLocationSnapshot snapshot(String latitude, String longitude, String accuracy) {
        return new RiderLocationSnapshot(
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                MEASURED_AT,
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    @Nested
    @DisplayName("왕복")
    class RoundTrip {

        @Test
        @DisplayName("인코딩한 값을 디코딩하면 같은 값이 나온다")
        void preservesValue() {
            RiderLocationSnapshot location = snapshot("37.4979", "127.0276", "12.5");

            assertThat(decode(encode(location))).contains(location);
        }

        @Test
        @DisplayName("정확도가 없어도 왕복된다")
        void preservesMissingAccuracy() {
            // split 의 limit 을 -1 로 주지 않으면 비어 있는 마지막 조각이 잘려 필드 수가 3개로
            // 세어지고, 정확도 없는 위치가 전부 "손상된 값"으로 취급된다. 그 회귀를 잡는 케이스다.
            RiderLocationSnapshot location = snapshot("37.4979", "127.0276", null);

            assertThat(decode(encode(location))).contains(location);
        }

        @Test
        @DisplayName("음수 좌표도 왕복된다")
        void preservesNegativeCoordinates() {
            // 남반구·서반구. 마이너스 부호가 구분자 처리와 섞이지 않는지 확인한다.
            RiderLocationSnapshot location = snapshot("-33.8688", "-70.6693", "5.25");

            assertThat(decode(encode(location))).contains(location);
        }

        @Test
        @DisplayName("측정 시각의 밀리초가 왕복에서 보존된다")
        void preservesSubSecondPrecision() {
            assertThat(decode(encode(snapshot("37.4979", "127.0276", null))))
                    .get()
                    .extracting(RiderLocationSnapshot::measuredAt)
                    .isEqualTo(MEASURED_AT);
        }
    }

    @Nested
    @DisplayName("형식")
    class Format {

        @Test
        @DisplayName("쉼표로 구분된 네 조각으로 인코딩된다")
        void encodesAsFourCommaSeparatedFields() {
            // 값 형식이 곧 스키마다. 필드 순서가 바뀌면 이미 Redis 에 있는 값이 오해석되므로
            // 형식 자체를 못 박아 둔다.
            String encoded = encode(snapshot("37.4979", "127.0276", "12.5"));

            assertThat(encoded).isEqualTo("37.4979000,127.0276000,2026-07-29T12:34:56.789,12.50");
        }

        @Test
        @DisplayName("정확도가 없으면 마지막 조각이 빈다")
        void leavesAccuracyFieldEmptyWhenAbsent() {
            assertThat(encode(snapshot("37.4979", "127.0276", null)))
                    .isEqualTo("37.4979000,127.0276000,2026-07-29T12:34:56.789,");
        }
    }

    @Nested
    @DisplayName("손상된 값")
    class CorruptedValue {

        @Test
        @DisplayName("조각 수가 모자라면 빈 결과다")
        void returnsEmptyWhenTooFewFields() {
            assertThat(decode("37.4979000,127.0276000,2026-07-29T12:34:56")).isEmpty();
        }

        @Test
        @DisplayName("조각 수가 많으면 빈 결과다")
        void returnsEmptyWhenTooManyFields() {
            assertThat(decode("37.4979000,127.0276000,2026-07-29T12:34:56,12.50,extra")).isEmpty();
        }

        @Test
        @DisplayName("좌표가 숫자가 아니면 예외가 아니라 빈 결과다")
        void returnsEmptyOnNonNumericCoordinate() {
            // 예외를 던지면 위치 갱신 API 전체가 500 이 된다. 잘못된 좌표를 배차·추적에 흘리는
            // 것보다 "위치 없음"이 낫다.
            assertThat(decode("north,127.0276000,2026-07-29T12:34:56,12.50")).isEmpty();
        }

        @Test
        @DisplayName("측정 시각 형식이 깨져도 빈 결과다")
        void returnsEmptyOnMalformedTimestamp() {
            assertThat(decode("37.4979000,127.0276000,2026-07-29 12:34:56,12.50")).isEmpty();
        }

        @Test
        @DisplayName("빈 문자열이면 빈 결과다")
        void returnsEmptyOnBlankValue() {
            assertThat(decode("")).isEmpty();
        }
    }
}
