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
 * 값 문자열 형식이고, Redis 왕복은 목으로 검증해도 아무것도 보장하지 못한다. 조건부 갱신
 * (Lua)의 동작은 실제 Redis 가 필요하므로 {@link RedisRiderLocationStoreIntegrationTest} 가 맡는다.
 *
 * <p>여기서 <b>고정 폭</b>과 <b>사전순 정합</b>을 못 박는 이유: Lua 스크립트가 값의 앞 23글자를
 * 잘라 사전순으로 비교한다. 그 전제가 깨지면 조건부 갱신이 조용히 틀린 판정을 내리는데, 그 실패는
 * "최신 위치가 가끔 과거로 되돌아감"으로 나타나 원인을 추적하기 어렵다.
 */
@DisplayName("RedisRiderLocationStore 값 인코딩")
class RedisRiderLocationStoreTest {

    private static final LocalDateTime MEASURED_AT =
            LocalDateTime.of(2026, 7, 29, 12, 34, 56, 789_000_000);

    /** 측정 시각 조각의 폭. Lua 스크립트가 이 값을 전제로 값의 앞을 잘라 비교한다. */
    private static final int MEASURED_AT_LENGTH = 23;

    private static RiderLocationSnapshot snapshot(String latitude, String longitude, String accuracy) {
        return snapshot(latitude, longitude, accuracy, MEASURED_AT);
    }

    private static RiderLocationSnapshot snapshot(String latitude, String longitude, String accuracy,
                                                  LocalDateTime measuredAt) {
        return new RiderLocationSnapshot(
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                measuredAt,
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    private static String encodedAt(LocalDateTime measuredAt) {
        return encode(snapshot("37.4979", "127.0276", null, measuredAt));
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
        @DisplayName("측정 시각을 맨 앞에 둔 쉼표 구분 네 조각으로 인코딩된다")
        void encodesAsFourCommaSeparatedFields() {
            // 값 형식이 곧 스키마다. 필드 순서가 바뀌면 이미 Redis 에 있는 값이 오해석되고,
            // 측정 시각이 맨 앞이 아니면 Lua 가 앞을 잘라 비교할 수 없다.
            String encoded = encode(snapshot("37.4979", "127.0276", "12.5"));

            assertThat(encoded).isEqualTo("2026-07-29T12:34:56.789,37.4979000,127.0276000,12.50");
        }

        @Test
        @DisplayName("정확도가 없으면 마지막 조각이 빈다")
        void leavesAccuracyFieldEmptyWhenAbsent() {
            assertThat(encode(snapshot("37.4979", "127.0276", null)))
                    .isEqualTo("2026-07-29T12:34:56.789,37.4979000,127.0276000,");
        }

        @Test
        @DisplayName("초·밀리초가 0이어도 측정 시각의 폭이 줄지 않는다")
        void keepsMeasuredAtWidthWhenSubSecondFieldsAreZero() {
            // LocalDateTime#toString 을 쓰던 이전 형식이 정확히 여기서 무너졌다 — 초가 0이면
            // ":00" 을 생략하고 나노초 자릿수도 0·3·6·9 로 달라져 길이가 가변이었다.
            assertThat(encodedAt(LocalDateTime.of(2026, 7, 29, 12, 0)))
                    .startsWith("2026-07-29T12:00:00.000,");
        }

        @Test
        @DisplayName("측정 시각 조각은 항상 같은 폭이다")
        void encodesMeasuredAtAtFixedWidth() {
            LocalDateTime[] cases = {
                    LocalDateTime.of(2026, 1, 1, 0, 0),
                    LocalDateTime.of(2026, 7, 29, 12, 34, 56),
                    LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000),
            };

            for (LocalDateTime measuredAt : cases) {
                assertThat(encodedAt(measuredAt).indexOf(','))
                        .as("측정 시각 조각의 폭 (measuredAt=%s)", measuredAt)
                        .isEqualTo(MEASURED_AT_LENGTH);
            }
        }

        @Test
        @DisplayName("측정 시각 조각의 사전순 비교가 시간순과 일치한다")
        void ordersMeasuredAtLexicographically() {
            // Lua 는 시각을 파싱하지 않고 문자열로 비교한다. 이 성질이 곧 조건부 갱신의 정확성이다.
            LocalDateTime earlier = LocalDateTime.of(2026, 7, 29, 12, 0, 0, 999_000_000);
            LocalDateTime later = LocalDateTime.of(2026, 7, 29, 12, 0, 1);

            assertThat(encodedAt(earlier).substring(0, MEASURED_AT_LENGTH))
                    .isLessThan(encodedAt(later).substring(0, MEASURED_AT_LENGTH));
        }
    }

    @Nested
    @DisplayName("손상된 값")
    class CorruptedValue {

        @Test
        @DisplayName("조각 수가 모자라면 빈 결과다")
        void returnsEmptyWhenTooFewFields() {
            assertThat(decode("2026-07-29T12:34:56.789,37.4979000,127.0276000")).isEmpty();
        }

        @Test
        @DisplayName("조각 수가 많으면 빈 결과다")
        void returnsEmptyWhenTooManyFields() {
            assertThat(decode("2026-07-29T12:34:56.789,37.4979000,127.0276000,12.50,extra")).isEmpty();
        }

        @Test
        @DisplayName("좌표가 숫자가 아니면 예외가 아니라 빈 결과다")
        void returnsEmptyOnNonNumericCoordinate() {
            // 예외를 던지면 위치 갱신 API 전체가 500 이 된다. 잘못된 좌표를 배차·추적에 흘리는
            // 것보다 "위치 없음"이 낫다.
            assertThat(decode("2026-07-29T12:34:56.789,north,127.0276000,12.50")).isEmpty();
        }

        @Test
        @DisplayName("측정 시각 형식이 깨져도 빈 결과다")
        void returnsEmptyOnMalformedTimestamp() {
            assertThat(decode("2026-07-29 12:34:56.789,37.4979000,127.0276000,12.50")).isEmpty();
        }

        @Test
        @DisplayName("이전 값 형식은 예외가 아니라 빈 결과다")
        void returnsEmptyOnPreviousValueFormat() {
            // #233 형식(좌표가 앞, 측정 시각이 LocalDateTime#toString). 배포 직후 Redis 에 남아
            // 있는 값이 이 형태다. 새 파서로는 읽히지 않아 최대 TTL(10분) 동안 "위치 없음"으로
            // 보이는데, 감내하기로 한 한계이므로 그 동작을 명시해 둔다 — 중요한 것은 예외가 나서
            // 위치 갱신 API 가 500 이 되지 않는 것이다.
            assertThat(decode("37.4979000,127.0276000,2026-07-29T12:34:56.789,12.50")).isEmpty();
        }

        @Test
        @DisplayName("밀리초 자리가 없으면 빈 결과다")
        void returnsEmptyOnMeasuredAtWithoutMilliseconds() {
            // 고정 폭이 아닌 시각은 받지 않는다. 받아 주면 Lua 의 앞자리 비교 전제가 깨진 값이
            // 저장소에 들어온다.
            assertThat(decode("2026-07-29T12:34:56,37.4979000,127.0276000,12.50")).isEmpty();
        }

        @Test
        @DisplayName("빈 문자열이면 빈 결과다")
        void returnsEmptyOnBlankValue() {
            assertThat(decode("")).isEmpty();
        }
    }
}
