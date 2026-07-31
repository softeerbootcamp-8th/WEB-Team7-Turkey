package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.dto.RiderLocationUpdateResponse;
import com.turkey.quick.location.sse.RecordingTrackingEventPublisher;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 스프링을 띄우지 않는다. 이 서비스는 DB 를 읽지 않고 트랜잭션 경계도 없어서, 통합 테스트로
 * 얻을 것이 없다(testing.md 의 층 구분 — 통합이 잡는 것은 트랜잭션·JPA 매핑·DB 제약·동시성이다).
 * 라이더가 DB 에 실제로 있어야 인터셉터가 통과한다는 검증은 #236 E2E 몫이다.
 *
 * 시각 경계값(정확히 5초 미래·정확히 60초 과거)은 {@link LocationAcceptancePolicyTest} 가 now 를
 * 인자로 받아 이미 정확히 검증했다. 그래서 여기서는 넉넉한 상대 오프셋만 쓴다 — 같은 것을 두 층에서
 * 검증하지 않는다.
 */
@DisplayName("RiderLocationService")
class RiderLocationServiceTest {

    private static final Long RIDER_ID = 7L;
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0276");

    private final InMemoryRiderLocationStore store = new InMemoryRiderLocationStore();
    private final RecordingTrackingEventPublisher publisher = new RecordingTrackingEventPublisher();
    private final RiderLocationService service = new RiderLocationService(store, publisher);

    /** 한 테스트 안에서 여러 요청의 측정 시각 순서를 정확히 통제하기 위해 기준 시각을 고정한다. */
    private final Instant baseNow = Instant.now();

    private RiderLocationUpdateRequest request(String latitude, long secondsAgo, String accuracy) {
        return new RiderLocationUpdateRequest(
                new BigDecimal(latitude),
                LONGITUDE,
                baseNow.minusSeconds(secondsAgo),
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    private RiderLocationUpdateRequest request(String latitude, long secondsAgo) {
        return request(latitude, secondsAgo, null);
    }

    private RiderLocationUpdateResponse updateAsBusy(RiderLocationUpdateRequest request) {
        return service.update(RIDER_ID, OperatingStatus.BUSY, request);
    }

    private BigDecimal storedLatitude() {
        return store.find(RIDER_ID).map(RiderLocationSnapshot::latitude).orElse(null);
    }

    @Nested
    @DisplayName("운행 상태")
    class OperatingStatusCheck {

        @Test
        @DisplayName("운행 중이 아니면 409로 거부한다")
        void rejectsWhenNotOperating() {
            assertThatThrownBy(() -> service.update(RIDER_ID, OperatingStatus.UNAVAILABLE, request("37.4979", 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("운행 중이 아니면 위치를 저장하지도 않는다")
        void doesNotStoreWhenNotOperating() {
            // 예외만 확인하고 부작용을 보지 않으면, 저장 후에 던지는 구현도 통과한다.
            // 운행 종료한 라이더의 좌표가 남으면 배차 후보 검색(#83)에 잡힌다.
            assertThatThrownBy(() -> service.update(RIDER_ID, OperatingStatus.UNAVAILABLE, request("37.4979", 0)))
                    .isInstanceOf(BusinessException.class);

            assertThat(store.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("AVAILABLE 이면 위치를 저장한다")
        void storesWhenAvailable() {
            service.update(RIDER_ID, OperatingStatus.AVAILABLE, request("37.4979", 0));

            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }

        @Test
        @DisplayName("BUSY 면 저장하고 구독자에게 발행한다")
        void storesAndPublishesWhenBusy() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
            assertThat(response.published()).isTrue();
        }
    }

    @Nested
    @DisplayName("폐기")
    class Discarding {

        @Test
        @DisplayName("오래된 측정 시각은 저장하지 않고 STALE 로 응답한다")
        void discardsStaleFix() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 120));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.STALE);
            assertThat(response.applied()).isFalse();
            assertThat(store.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("정확도가 기준에 못 미치면 저장하지 않고 LOW_ACCURACY 로 응답한다")
        void discardsLowAccuracyFix() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0, "150"));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.LOW_ACCURACY);
            assertThat(store.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("미래 측정 시각은 폐기가 아니라 예외다")
        void propagatesFutureTimestampAsException() {
            // 200 폐기가 아니라 400 이다. 정책이 던진 예외를 감싸지 않고 그대로 올려보내야
            // GlobalExceptionHandler 가 원래 한국어 메시지를 응답 본문에 쓴다.
            assertThatThrownBy(() -> updateAsBusy(request("37.4979", -60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("측정 시각");

            assertThat(store.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("측정 시각 단조성")
    class Monotonicity {

        /**
         * 기준 좌표에서 약 55m 북쪽. 최소 이동 거리(20m)를 넘으면서 30초에 1.85 m/s 라 허용 속도
         * 안이다 — #82 필터가 붙은 뒤로는 픽스처 좌표도 물리적으로 가능한 값이어야 한다.
         */
        private static final String NEARBY = "37.4984";

        @Test
        @DisplayName("이전 위치가 없으면 첫 좌표를 받아들인다")
        void acceptsFirstFix() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 20));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("이전보다 과거인 좌표는 최신 위치를 덮지 않는다")
        void keepsLatestWhenOlderFixArrives() {
            updateAsBusy(request("37.4979", 20));

            RiderLocationUpdateResponse response = updateAsBusy(request(NEARBY, 40));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.NON_MONOTONIC);
            assertThat(response.applied()).isFalse();
            // 폐기한 좌표가 기준선을 오염시키지 않았는지가 핵심이다.
            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }

        @Test
        @DisplayName("이전과 같은 시각의 재전송도 덮지 않는다")
        void rejectsResendWithSameTimestamp() {
            // 같은 좌표 재전송이라 덮어써도 값은 같지만 Redis 쓰기만 늘어난다.
            updateAsBusy(request("37.4979", 20));

            RiderLocationUpdateResponse response = updateAsBusy(request(NEARBY, 20));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.NON_MONOTONIC);
            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }

        @Test
        @DisplayName("이전보다 최신인 좌표는 최신 위치를 갱신한다")
        void updatesLatestWhenNewerFixArrives() {
            updateAsBusy(request("37.4979", 40));

            RiderLocationUpdateResponse response = updateAsBusy(request(NEARBY, 10));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
            assertThat(storedLatitude()).isEqualByComparingTo(NEARBY);
        }
    }

    @Nested
    @DisplayName("중복·이상 이동 필터")
    class Filtering {

        /** 위도 +0.0001도 ≈ 11m — 최소 이동 거리(20m) 미만. */
        private static final String SMALL_STEP = "0.0001";

        private RiderLocationUpdateRequest movedNorth(int steps, long secondsAgo) {
            BigDecimal latitude = new BigDecimal("37.4979")
                    .add(new BigDecimal(SMALL_STEP).multiply(BigDecimal.valueOf(steps)));
            return new RiderLocationUpdateRequest(latitude, LONGITUDE, baseNow.minusSeconds(secondsAgo), null);
        }

        @Test
        @DisplayName("의미 있게 움직이면 저장하고 전송 대상이 된다")
        void publishesRealMovement() {
            updateAsBusy(movedNorth(0, 30));

            var response = updateAsBusy(movedNorth(5, 20));   // 약 55m

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
            assertThat(response.applied()).isTrue();
            assertThat(storedLatitude()).isEqualByComparingTo(movedNorth(5, 20).latitude());
        }

        @Test
        @DisplayName("최소 이동 거리 미만이면 저장은 되고 전송 대상은 아니다")
        void storesButDoesNotPublishSmallMovement() {
            updateAsBusy(movedNorth(0, 30));

            var response = updateAsBusy(movedNorth(1, 20));   // 약 11m

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.DUPLICATE);
            assertThat(response.applied()).isTrue();
            assertThat(response.published()).isFalse();
        }

        @Test
        @DisplayName("최소 이동 거리 미만일 때 좌표는 그대로 두고 TTL 만 갱신한다")
        void keepsBaselineOnSmallMovement() {
            // 새 좌표로 덮으면 기준선이 조금씩 전진해, 느리게 움직이는 라이더가 영원히 전송
            // 대상이 되지 않는다. 아래 누적 이동 테스트가 그 결과를 검증한다.
            updateAsBusy(movedNorth(0, 30));

            updateAsBusy(movedNorth(1, 20));

            assertThat(storedLatitude()).isEqualByComparingTo(movedNorth(0, 30).latitude());
        }

        @Test
        @DisplayName("느린 이동도 누적되면 전송 대상이 된다")
        void publishesOnceSlowMovementAccumulates() {
            // 이 테스트가 "중복이면 좌표를 덮지 않는다"의 존재 이유다. 매번 11m 씩 움직이는
            // 라이더는 직전 대비로는 항상 최소 이동 거리 미만이지만, 기준선이 고정돼 있으므로
            // 누적 22m 가 되는 순간 전송 대상이 된다. 기준선을 전진시키면 영원히 전송되지 않는다.
            updateAsBusy(movedNorth(0, 30));

            assertThat(updateAsBusy(movedNorth(1, 25)).reason())
                    .isEqualTo(LocationUpdateOutcome.DUPLICATE);
            assertThat(updateAsBusy(movedNorth(2, 20)).reason())
                    .isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("이상 속도는 저장하지 않고 기준선을 오염시키지 않는다")
        void doesNotStoreImplausibleMovement() {
            updateAsBusy(request("37.4979", 20));

            // 10초 만에 위도 1도(약 111km) 이동 → 약 11,100 m/s
            var response = updateAsBusy(request("38.4979", 10));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.IMPLAUSIBLE_SPEED);
            assertThat(response.applied()).isFalse();
            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }

        @Test
        @DisplayName("첫 좌표는 필터를 통과한다")
        void acceptsFirstFixWithoutBaseline() {
            var response = updateAsBusy(request("37.4979", 10));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("원자적 조건부 갱신")
    class ConditionalUpdate {

        @Test
        @DisplayName("사전 검사를 통과한 뒤 다른 인스턴스가 더 최신을 쓰면 NON_MONOTONIC 이다")
        void reportsNonMonotonicWhenLosingRace() {
            // 사전 단조성 검사만으로는 막히지 않는 창이다 — find 시점에는 통과했고, 쓰기 시점에
            // 저장된 값이 이미 더 최신이다. 수평 확장에서 같은 라이더의 요청 둘이 다른 인스턴스로
            // 갈라졌을 때 실제로 일어난다(#250).
            RiderLocationSnapshot winner = new RiderLocationSnapshot(
                    new BigDecimal("37.5000"), LONGITUDE, baseNow.atZone(ZoneOffset.UTC).toLocalDateTime(), null);
            var racingStore = new StoreLosingRaceAfterFind(winner);
            var racingService = new RiderLocationService(racingStore, publisher);

            RiderLocationUpdateResponse response =
                    racingService.update(RIDER_ID, OperatingStatus.BUSY, request("37.4979", 10));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.NON_MONOTONIC);
            assertThat(response.applied()).isFalse();
            // 경쟁에서 이긴 좌표가 뒤늦은 요청에 덮이지 않았다는 것이 이 이슈의 요점이다.
            assertThat(racingStore.find(RIDER_ID)).contains(winner);
        }
    }

    /**
     * {@code find} 직후에 다른 인스턴스가 더 최신 좌표를 써 넣은 상태를 재현한다. 서비스는 이전
     * 위치를 한 번만 읽으므로, 그 읽기 뒤에 값이 바뀌는 것이 정확히 경쟁 창이다.
     *
     * <p>한 번만 끼워 넣는다 — 매번 넣으면 어떤 요청도 영원히 저장되지 않아, 테스트가 "경쟁에서
     * 졌다"가 아니라 "저장이 아예 안 된다"를 검증하게 된다.
     */
    private static class StoreLosingRaceAfterFind extends InMemoryRiderLocationStore {

        private final RiderLocationSnapshot winner;
        private boolean injected;

        StoreLosingRaceAfterFind(RiderLocationSnapshot winner) {
            this.winner = winner;
        }

        @Override
        public Optional<RiderLocationSnapshot> find(Long riderId) {
            Optional<RiderLocationSnapshot> seen = super.find(riderId);
            if (!injected) {
                injected = true;
                super.saveIfNewer(riderId, winner);
            }
            return seen;
        }
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        @DisplayName("수용한 요청은 갱신됨·전파됨·ACCEPTED 로 응답한다")
        void describesAcceptedUpdate() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(response.applied()).isTrue();
            assertThat(response.published()).isTrue();
            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("진행 중 배송이 없으면 갱신은 되고 전파는 false 다")
        void reportsUnpublishedWithoutInProgressDelivery() {
            // AVAILABLE 라이더의 30초 주기 위치가 대부분 이 경우다. published 를 배달 보장이나
            // "고객이 보고 있음"으로 읽으면 이 정상 상태를 실패로 오해한다.
            publisher.withoutInProgressDelivery();

            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(response.applied()).isTrue();
            assertThat(response.published()).isFalse();
            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("구독자 전파")
    class Publishing {

        @Test
        @DisplayName("수용한 좌표만 발행한다")
        void publishesAcceptedLocation() {
            updateAsBusy(request("37.4979", 0));

            assertThat(publisher.published())
                    .extracting(RiderLocationSnapshot::latitude)
                    .containsExactly(new BigDecimal("37.4979000"));
        }

        @Test
        @DisplayName("폐기한 좌표는 발행하지 않는다")
        void doesNotPublishDiscardedLocation() {
            // 값 자체를 믿을 수 없는 경우다. 발행하면 고객 지도가 잘못된 좌표로 튄다.
            updateAsBusy(request("37.4979", 120));                 // STALE
            updateAsBusy(request("37.4979", 0, "150"));             // LOW_ACCURACY

            assertThat(publisher.published()).isEmpty();
        }

        @Test
        @DisplayName("중복 판정은 저장만 하고 발행하지 않는다")
        void doesNotPublishDuplicate() {
            // 정지한 라이더가 주기마다 만들어 낸다. 같은 좌표를 고객 지도에 다시 밀 이유가 없다.
            updateAsBusy(request("37.4979", 30));
            publisher.published();

            var response = updateAsBusy(new RiderLocationUpdateRequest(
                    new BigDecimal("37.49791"), LONGITUDE, baseNow.minusSeconds(20), null));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.DUPLICATE);
            assertThat(response.published()).isFalse();
            assertThat(publisher.published()).hasSize(1);
        }

        @Test
        @DisplayName("발행이 예외를 던져도 위치 갱신은 성공한다")
        void survivesPublishFailure() {
            // 이 경로가 깨지면 Redis·MySQL 장애가 고객 추적을 넘어 배차 후보 검색(#83)이 쓰는
            // 최신 위치 갱신까지 같이 죽인다. 그래서 발행 실패는 삼키고 200 을 돌려준다.
            publisher.throwing(new IllegalStateException("Redis 장애"));

            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(response.applied()).isTrue();
            assertThat(response.published()).isFalse();
            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }
    }
}
