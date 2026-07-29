package com.turkey.quick.location.service;

import static com.turkey.quick.location.service.LocationFilter.decide;
import static com.turkey.quick.location.service.LocationFilter.haversineMeters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withPrecision;

import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LocationFilter")
class LocationFilterTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 29, 12, 0, 0);

    /** 서울시청 · 강남역 — 하버사인 검증용 알려진 좌표쌍. */
    private static final BigDecimal CITY_HALL_LAT = new BigDecimal("37.5665");
    private static final BigDecimal CITY_HALL_LON = new BigDecimal("126.9780");
    private static final BigDecimal GANGNAM_LAT = new BigDecimal("37.4979");
    private static final BigDecimal GANGNAM_LON = new BigDecimal("127.0276");

    private static RiderLocationSnapshot at(BigDecimal latitude, BigDecimal longitude, LocalDateTime measuredAt) {
        return new RiderLocationSnapshot(latitude, longitude, measuredAt, null);
    }

    /** 위도만 옮긴 좌표. 위도 1e-7도 ≈ 1.1cm 이므로 미세한 거리를 만들기 쉽다. */
    private static RiderLocationSnapshot movedNorth(String latitudeDelta, Duration after) {
        return at(CITY_HALL_LAT.add(new BigDecimal(latitudeDelta)), CITY_HALL_LON, BASE.plus(after));
    }

    private static RiderLocationSnapshot origin() {
        return at(CITY_HALL_LAT, CITY_HALL_LON, BASE);
    }

    @Nested
    @DisplayName("하버사인 거리")
    class Haversine {

        @Test
        @DisplayName("같은 좌표는 0m다")
        void returnsZeroForSamePoint() {
            assertThat(haversineMeters(origin(), origin())).isEqualTo(0.0, withPrecision(1e-9));
        }

        @Test
        @DisplayName("알려진 좌표쌍의 거리가 실측과 맞는다")
        void matchesKnownPair() {
            // 서울시청 → 강남역 직선거리. 지도 실측 약 8.8km.
            double meters = haversineMeters(
                    origin(), at(GANGNAM_LAT, GANGNAM_LON, BASE));

            assertThat(meters).isCloseTo(8_790, org.assertj.core.data.Percentage.withPercentage(3));
        }

        @Test
        @DisplayName("거리는 방향에 무관하다")
        void isSymmetric() {
            var a = origin();
            var b = at(GANGNAM_LAT, GANGNAM_LON, BASE);

            assertThat(haversineMeters(a, b)).isEqualTo(haversineMeters(b, a), withPrecision(1e-6));
        }

        @Test
        @DisplayName("날짜변경선을 넘어도 지구 반대편으로 계산되지 않는다")
        void handlesAntimeridian() {
            // 경도 179.9999 ↔ -179.9999 는 적도에서 약 22m 다. 단순 차이로 계산하면 약 4만km 가 된다.
            var west = at(BigDecimal.ZERO, new BigDecimal("179.9999"), BASE);
            var east = at(BigDecimal.ZERO, new BigDecimal("-179.9999"), BASE);

            assertThat(haversineMeters(west, east)).isLessThan(50);
        }
    }

    @Nested
    @DisplayName("최소 이동 거리")
    class MinimumDistance {

        @Test
        @DisplayName("이전 위치가 없으면 받아들인다")
        void acceptsFirstFix() {
            assertThat(decide(null, origin())).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("완전히 같은 좌표는 중복이다")
        void treatsIdenticalPointAsDuplicate() {
            var next = at(CITY_HALL_LAT, CITY_HALL_LON, BASE.plusSeconds(5));

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.DUPLICATE);
        }

        @Test
        @DisplayName("최소 이동 거리 미만은 중복이다")
        void treatsSmallMoveAsDuplicate() {
            // 위도 +0.0001도 ≈ 11m
            var next = movedNorth("0.0001", Duration.ofSeconds(5));

            assertThat(haversineMeters(origin(), next)).isLessThan(20);
            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.DUPLICATE);
        }

        @Test
        @DisplayName("최소 이동 거리 이상은 받아들인다")
        void acceptsMoveBeyondThreshold() {
            // 위도 +0.0002도 ≈ 22m
            var next = movedNorth("0.0002", Duration.ofSeconds(5));

            assertThat(haversineMeters(origin(), next)).isGreaterThan(20);
            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("허용 속도")
    class SpeedLimit {

        /**
         * 거리를 먼저 재고 목표 속도가 나오도록 경과 시간을 역산한다.
         *
         * 나노초로 <b>올림</b>하므로 실제 속도는 목표값 이하가 된다. 반올림하면 목표가 50일 때
         * 실제 속도가 50을 미세하게 넘어 판정이 뒤집힌다 — 부동소수로 유도한 값에서 정확한 경계
         * 등호(속도 == 50.000)는 만들 수 없다. 그래서 "상한 이하"와 "상한 초과"를 각각 검증하고,
         * 실제 속도가 의도한 쪽에 있는지 테스트 안에서 함께 단언한다.
         */
        private Duration elapsedForAtMostSpeed(RiderLocationSnapshot target, double metersPerSecond) {
            double meters = haversineMeters(origin(), target);
            return Duration.ofNanos((long) Math.ceil(meters / metersPerSecond * 1_000_000_000.0));
        }

        private double speedOf(RiderLocationSnapshot next) {
            double meters = haversineMeters(origin(), next);
            double seconds = Duration.between(BASE, next.measuredAt()).toNanos() / 1_000_000_000.0;
            return meters / seconds;
        }

        @Test
        @DisplayName("허용 속도 이하면 받아들인다")
        void acceptsSpeedAtOrBelowLimit() {
            var far = movedNorth("0.005", Duration.ZERO);   // 약 555m
            var next = at(far.latitude(), far.longitude(), BASE.plus(elapsedForAtMostSpeed(far, 50)));

            assertThat(speedOf(next)).isLessThanOrEqualTo(50);
            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("허용 속도를 아주 조금만 넘어도 폐기한다")
        void discardsSpeedJustAboveLimit() {
            // 50.1 m/s. 임계값이 실제로 50에 있는지 확인하는 케이스다 — 55·180 처럼 넉넉한 값만
            // 쓰면 임계값이 60이어도 통과한다.
            var far = movedNorth("0.005", Duration.ZERO);
            var next = at(far.latitude(), far.longitude(), BASE.plus(elapsedForAtMostSpeed(far, 50.1)));

            assertThat(speedOf(next)).isGreaterThan(50);
            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.IMPLAUSIBLE_SPEED);
        }

        @Test
        @DisplayName("경과 1초 미만이면 속도를 검사하지 않는다")
        void skipsSpeedCheckBelowElapsedFloor() {
            // 0.5초에 555m 는 1110 m/s 지만, 분모가 잡음인 구간이라 거리 기준만 적용한다.
            var next = movedNorth("0.005", Duration.ofMillis(500));

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("경과 1초에서는 속도를 검사한다")
        void appliesSpeedCheckAtElapsedFloor() {
            var next = movedNorth("0.005", Duration.ofSeconds(1));   // 약 555 m/s

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.IMPLAUSIBLE_SPEED);
        }

        @Test
        @DisplayName("경과 30초에서는 아직 속도를 검사한다")
        void appliesSpeedCheckAtElapsedCeiling() {
            // 30초에 5.5km 는 약 185 m/s
            var next = movedNorth("0.05", Duration.ofSeconds(30));

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.IMPLAUSIBLE_SPEED);
        }

        @Test
        @DisplayName("경과 30초를 넘으면 속도를 검사하지 않는다")
        void skipsSpeedCheckBeyondElapsedCeiling() {
            // 앱이 백그라운드였거나 터널을 지난 경우다. 그 사이의 큰 이동은 정상이다.
            var next = movedNorth("0.05", Duration.ofSeconds(31));

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("경과가 0이면 속도를 검사하지 않는다")
        void skipsSpeedCheckOnZeroElapsed() {
            // 단조성 검사(#235)가 먼저 걸러 내므로 정상 흐름에서는 오지 않지만, 음수 분모로
            // 엉뚱한 판정을 내지 않는지 확인한다.
            var next = movedNorth("0.005", Duration.ZERO);

            assertThat(decide(origin(), next)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("기준선 오염 복구")
    class BaselineRecovery {

        @Test
        @DisplayName("오염된 기준선은 30초가 지나면 새 좌표로 교체된다")
        void recoversFromPoisonedBaselineAfterCeiling() {
            // 시나리오: 최초 좌표가 측위 오류로 엉뚱한 곳(적도)에 찍혔다. 이후 실제 좌표는 전부
            // 이상 속도로 보인다. 거부된 좌표는 기준을 전진시키지 않으므로 기준은 계속 늙고,
            // 30초를 넘긴 시점부터 새 좌표가 기준으로 받아들여진다.
            var poisoned = at(BigDecimal.ZERO, BigDecimal.ZERO, BASE);

            var tooSoon = at(CITY_HALL_LAT, CITY_HALL_LON, BASE.plusSeconds(5));
            assertThat(decide(poisoned, tooSoon)).isEqualTo(LocationUpdateOutcome.IMPLAUSIBLE_SPEED);

            var afterCeiling = at(CITY_HALL_LAT, CITY_HALL_LON, BASE.plusSeconds(31));
            assertThat(decide(poisoned, afterCeiling)).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("판정별 저장·전송 여부")
    class StoreAndPublish {

        @Test
        @DisplayName("정상 이동은 저장하고 전송한다")
        void storesAndPublishesAccepted() {
            assertThat(LocationUpdateOutcome.ACCEPTED.shouldStore()).isTrue();
            assertThat(LocationUpdateOutcome.ACCEPTED.shouldPublish()).isTrue();
        }

        @Test
        @DisplayName("중복은 저장하지만 전송하지 않는다")
        void storesButDoesNotPublishDuplicate() {
            // 정지한 라이더도 Redis 키 TTL 을 갱신해야 배차 후보 검색(#83)에서 빠지지 않는다.
            // 이 한 줄이 #82 의 핵심이다.
            assertThat(LocationUpdateOutcome.DUPLICATE.shouldStore()).isTrue();
            assertThat(LocationUpdateOutcome.DUPLICATE.shouldPublish()).isFalse();
        }

        @Test
        @DisplayName("이상 속도는 저장도 전송도 하지 않는다")
        void discardsImplausibleSpeed() {
            // 저장하면 잘못된 좌표가 다음 판정의 기준이 된다.
            assertThat(LocationUpdateOutcome.IMPLAUSIBLE_SPEED.shouldStore()).isFalse();
            assertThat(LocationUpdateOutcome.IMPLAUSIBLE_SPEED.shouldPublish()).isFalse();
        }

        @Test
        @DisplayName("값 자체를 믿을 수 없는 판정도 저장하지 않는다")
        void discardsUntrustworthyOutcomes() {
            for (var outcome : new LocationUpdateOutcome[]{
                    LocationUpdateOutcome.STALE,
                    LocationUpdateOutcome.LOW_ACCURACY,
                    LocationUpdateOutcome.NON_MONOTONIC}) {
                assertThat(outcome.shouldStore()).as("%s", outcome).isFalse();
                assertThat(outcome.shouldPublish()).as("%s", outcome).isFalse();
            }
        }
    }
}
