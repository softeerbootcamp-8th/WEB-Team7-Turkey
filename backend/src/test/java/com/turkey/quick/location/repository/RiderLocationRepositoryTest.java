package com.turkey.quick.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.LocationPayload;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code encode} 형식 계약만 검증한다. Lua 조건부 갱신의 실제 원자성은 여기서 알 수 없어
 * {@code RiderLocationRepositoryIntegrationTest} 가 실제 Redis 로 따로 본다.
 */
@DisplayName("RiderLocationRepository — 값 인코딩")
class RiderLocationRepositoryTest {

    private static LocationPayload at(String measuredAt) {
        return new LocationPayload(new BigDecimal("37.4979"), new BigDecimal("127.0276"),
                Instant.parse(measuredAt), new BigDecimal("12.5"));
    }

    @Test
    @DisplayName("measuredAt,latitude,longitude,accuracyMeters 순서로 합친다")
    void joinsFieldsInOrder() {
        assertThat(RiderLocationRepository.encode(at("2026-08-03T01:02:03.456Z")))
                .isEqualTo("2026-08-03T01:02:03.456,37.4979,127.0276,12.5");
    }

    @Test
    @DisplayName("정확도가 없으면 마지막 조각을 비운다")
    void leavesAccuracySegmentEmptyWhenAbsent() {
        LocationPayload noAccuracy = new LocationPayload(new BigDecimal("37.4979"),
                new BigDecimal("127.0276"), Instant.parse("2026-08-03T01:02:03.456Z"), null);

        assertThat(RiderLocationRepository.encode(noAccuracy))
                .isEqualTo("2026-08-03T01:02:03.456,37.4979,127.0276,");
    }

    @Test
    @DisplayName("측정 시각 접두어는 항상 23자다 — Lua 가 이 폭을 전제로 자른다")
    void measuredAtPrefixIsAlwaysFixedWidth() {
        // 밀리초·초가 0이면 자릿수가 줄어드는 포맷을 쓰면 사전순 비교가 시간순과 어긋난다.
        // 그 순간 조건부 갱신이 조용히 오작동하므로 폭 자체를 못 박는다.
        assertThat(RiderLocationRepository.encode(at("2026-08-03T01:02:03.456Z")).substring(0, 23))
                .isEqualTo("2026-08-03T01:02:03.456");
        assertThat(RiderLocationRepository.encode(at("2026-08-03T00:00:00Z")).substring(0, 23))
                .isEqualTo("2026-08-03T00:00:00.000");
        assertThat(RiderLocationRepository.encode(at("2026-12-31T23:59:59.900Z")).substring(0, 23))
                .isEqualTo("2026-12-31T23:59:59.900");
    }

    @Test
    @DisplayName("측정 시각이 앞설수록 사전순으로도 작다")
    void lexicographicOrderMatchesChronologicalOrder() {
        // Lua 조건부 갱신이 문자열 비교로 시각을 판정하므로, 이 성질이 깨지면 오래된 좌표가
        // 최신을 덮는다. 해를 넘기는 경계와 자정 경계를 함께 본다.
        String earlier = RiderLocationRepository.encode(at("2026-08-03T01:02:03.456Z"));
        String later = RiderLocationRepository.encode(at("2026-08-03T01:02:03.457Z"));
        String nextDay = RiderLocationRepository.encode(at("2026-08-04T00:00:00Z"));
        String nextYear = RiderLocationRepository.encode(at("2027-01-01T00:00:00Z"));

        assertThat(earlier).isLessThan(later);
        assertThat(later).isLessThan(nextDay);
        assertThat(nextDay).isLessThan(nextYear);
    }

    @Test
    @DisplayName("시간대와 무관하게 같은 순간은 같은 문자열이 된다")
    void encodesInstantsInUtcRegardlessOfDefaultZone() {
        // Instant 를 포맷하려면 시간대가 필요하다. 시스템 기본값을 쓰면 인스턴스마다 같은 순간이
        // 다른 문자열로 저장돼 인스턴스 간 비교가 무의미해진다.
        assertThat(RiderLocationRepository.encode(at("2026-08-03T01:02:03.456Z")))
                .startsWith("2026-08-03T01:02:03.456");
    }

    @Nested
    @DisplayName("decode — encode 의 역변환(#311)")
    class Decode {

        @Test
        @DisplayName("encode 한 값을 그대로 복원한다")
        void roundTripsWithEncode() {
            LocationPayload location = at("2026-08-03T01:02:03.456Z");

            LocationPayload decoded = RiderLocationRepository.decode(RiderLocationRepository.encode(location));

            assertThat(decoded).isEqualTo(location);
        }

        @Test
        @DisplayName("정확도가 없는 값도 복원한다")
        void roundTripsWithoutAccuracy() {
            LocationPayload noAccuracy = new LocationPayload(new BigDecimal("37.4979"),
                    new BigDecimal("127.0276"), Instant.parse("2026-08-03T01:02:03.456Z"), null);

            LocationPayload decoded = RiderLocationRepository.decode(RiderLocationRepository.encode(noAccuracy));

            assertThat(decoded).isEqualTo(noAccuracy);
        }

        @Test
        @DisplayName("필드 개수가 다르면 null 을 돌려준다")
        void returnsNullWhenFieldCountMismatches() {
            // #297 이전 형식(좌표가 앞) 등 다른 필드 개수의 값이 남아 있을 수 있다.
            assertThat(RiderLocationRepository.decode("37.4979,127.0276")).isNull();
        }

        @Test
        @DisplayName("손상된 값은 예외 대신 null 을 돌려준다")
        void returnsNullForCorruptedValue() {
            assertThat(RiderLocationRepository.decode("garbage-not-a-location,,,")).isNull();
        }
    }
}
