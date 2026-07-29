package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.dto.RiderLocationUpdateResponse;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.math.BigDecimal;
import java.time.Instant;
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
    private final RiderLocationService service = new RiderLocationService(store);

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
        @DisplayName("BUSY 여도 이 이슈에서는 저장까지만 한다")
        void storesWithoutPublishingWhenBusy() {
            // BUSY 라이더의 좌표를 배정 주문 구독자에게 발행하는 것은 #78 이다.
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
            assertThat(response.published()).isFalse();
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

            RiderLocationUpdateResponse response = updateAsBusy(request("37.9999", 40));

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

            RiderLocationUpdateResponse response = updateAsBusy(request("37.9999", 20));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.NON_MONOTONIC);
            assertThat(storedLatitude()).isEqualByComparingTo("37.4979");
        }

        @Test
        @DisplayName("이전보다 최신인 좌표는 최신 위치를 갱신한다")
        void updatesLatestWhenNewerFixArrives() {
            updateAsBusy(request("37.4979", 40));

            RiderLocationUpdateResponse response = updateAsBusy(request("37.9999", 10));

            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
            assertThat(storedLatitude()).isEqualByComparingTo("37.9999");
        }
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        @DisplayName("수용한 요청은 갱신됨·미전파·ACCEPTED 로 응답한다")
        void describesAcceptedUpdate() {
            RiderLocationUpdateResponse response = updateAsBusy(request("37.4979", 0));

            assertThat(response.applied()).isTrue();
            assertThat(response.published()).isFalse();
            assertThat(response.reason()).isEqualTo(LocationUpdateOutcome.ACCEPTED);
        }
    }
}
