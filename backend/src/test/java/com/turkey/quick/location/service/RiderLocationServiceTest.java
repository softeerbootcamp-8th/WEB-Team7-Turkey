package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.InProgressDelivery;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 위치 스트리밍·저장은 <b>BUSY 전용</b>이다(디스커션 #338, #342). BUSY 만 최신 위치를 저장하고
 * 수행 중 배송 채널로 발행하며, 그 밖의 상태(AVAILABLE·UNAVAILABLE)는 409 로 거부한다 — 라이더-측
 * GEO 사용처를 제거하면서 AVAILABLE 이 위치를 저장·발행할 이유가 사라졌다.
 *
 * <p><b>배송 식별자를 요청에서 받지 않는다</b>는 것이 이 서비스의 핵심이라, "DB 로 풀어낸 배송으로만
 * 발행한다"를 여러 각도로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RiderLocationService")
class RiderLocationServiceTest {

    private static final Long RIDER_ID = 1L;
    private static final Long DELIVERY_ID = 10L;
    private static final LocationPayload LOCATION = new LocationPayload(
            new BigDecimal("37.5000000"), new BigDecimal("127.0000000"),
            Instant.parse("2026-08-03T01:02:03.456Z"), null);

    @InjectMocks
    private RiderLocationService service;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private RiderLocationRepository riderLocationRepository;

    @Mock
    private TrackingPublisher trackingPublisher;

    private AuthenticatedRider rider(OperatingStatus status) {
        return new AuthenticatedRider(RIDER_ID, "rider1", "홍라이더", status);
    }

    /** 조회는 이제 식별자와 상태를 함께 돌려준다(#449). 상태는 네이티브 쿼리라 문자열이다. */
    private void givenInProgressDelivery() {
        givenInProgressDelivery(OrderStatus.DELIVERING);
    }

    private void givenInProgressDelivery(OrderStatus status) {
        InProgressDelivery projection = mock(InProgressDelivery.class);
        given(projection.getOrderId()).willReturn(DELIVERY_ID);
        given(projection.getStatus()).willReturn(status.name());
        given(deliveryOrderRepository.findInProgressByActiveRiderId(RIDER_ID))
                .willReturn(Optional.of(projection));
    }

    @Nested
    @DisplayName("BUSY")
    class Busy {

        @Test
        @DisplayName("DB 로 풀어낸 수행 중 배송 채널로 발행한다")
        void publishesToDeliveryResolvedFromDatabase() {
            // 요청 본문이 배송 id 를 담지 않으므로, 발행 대상은 전적으로 이 조회 결과다.
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(trackingPublisher).should().publish(DELIVERY_ID, LOCATION.withStatus(OrderStatus.DELIVERING));
        }

        @Test
        @DisplayName("이력 전체를 훑는 조회가 아니라 유니크 인덱스 조회를 쓴다")
        void usesUniqueIndexLookupNotHistoryScan() {
            // 5초 주기 핫패스다. findInProgressByRiderId 는 idx_delivery_rider_completed 로 그
            // 라이더의 전체 주문 이력을 훑어서 운행 기간이 길어질수록 비용이 커진다.
            //
            // "완료·취소된 배송은 걸리지 않는다"는 의미론은 여기서 검증할 수 없다 — 그 조건이 이제
            // DDL 의 생성 컬럼(active_rider_id)에 있다. DeliveryOrderActiveRiderIntegrationTest 가
            // 실제 DB 로 그 동치를 고정한다.
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(deliveryOrderRepository).should().findInProgressByActiveRiderId(RIDER_ID);
            then(deliveryOrderRepository).should(never()).findInProgressByRiderId(anyLong(), anySet());
        }

        @Test
        @DisplayName("최신 위치를 저장한다")
        void savesLatestLocation() {
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(riderLocationRepository).should().saveIfNewer(RIDER_ID, LOCATION);
        }


        @Test
        @DisplayName("발행 프레임에는 현재 배송 상태가 실린다 (#449)")
        void publishedFrameCarriesCurrentStatus() {
            // 상태 전이 이벤트는 전이 시점에 한 번만 오므로 유실되면 다시 오지 않는다. 주기적으로
            // 흐르는 이 위치 프레임이 상태를 실어 나르는 것이 그 유실의 유일한 자연 복구 수단이다.
            givenInProgressDelivery(OrderStatus.PICKED_UP);

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(trackingPublisher).should().publish(DELIVERY_ID, LOCATION.withStatus(OrderStatus.PICKED_UP));
        }

        @Test
        @DisplayName("Redis 최신 위치에는 상태를 저장하지 않는다 (#449)")
        void doesNotPersistStatusToLatestLocation() {
            // 저장소는 위치 저장소이지 주문 상태 저장소가 아니다. 여기에 상태가 섞이면 쉼표 구분
            // Redis 값 형식이 바뀌어 배포 호환성 표면이 하나 더 늘어난다 — 원본을 그대로 넘긴다.
            givenInProgressDelivery(OrderStatus.PICKED_UP);

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(riderLocationRepository).should().saveIfNewer(RIDER_ID, LOCATION);
            assertThat(LOCATION.status()).isNull();
        }

        @Test
        @DisplayName("수행 중 배송이 없으면 발행하지 않고 조용히 끝낸다")
        void skipsPublishWhenNoInProgressDelivery() {
            // 운행 상태는 BUSY 인데 배정된 배송이 없는 정합성 깨진 상태다. 위치 갱신 자체는
            // 실패시키지 않고(로그로 드러낸다) 발행만 건너뛴다.
            given(deliveryOrderRepository.findInProgressByActiveRiderId(RIDER_ID))
                    .willReturn(Optional.empty());

            assertThatCode(() -> service.update(rider(OperatingStatus.BUSY), LOCATION))
                    .doesNotThrowAnyException();

            then(trackingPublisher).should(never()).publish(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("실패 격리")
    class FailureIsolation {

        @Test
        @DisplayName("최신 위치 저장(Redis)이 실패해도 발행은 계속된다")
        void publishesEvenWhenRedisSaveFails() {
            willThrow(new RuntimeException("redis down"))
                    .given(riderLocationRepository).saveIfNewer(RIDER_ID, LOCATION);
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(trackingPublisher).should().publish(DELIVERY_ID, LOCATION.withStatus(OrderStatus.DELIVERING));
        }

        @Test
        @DisplayName("배송 조회(MySQL)가 실패해도 위치 갱신은 성공한다")
        void survivesDatabaseFailure() {
            // 이 경로에 MySQL 의존이 있다. DB 장애가 최신 위치 저장까지 같이 죽이면 안 된다 —
            // 전달은 at-most-once 이고 다음 전송(5초)이 복구한다.
            willThrow(new RuntimeException("mysql down")).given(deliveryOrderRepository)
                    .findInProgressByActiveRiderId(RIDER_ID);

            assertThatCode(() -> service.update(rider(OperatingStatus.BUSY), LOCATION))
                    .doesNotThrowAnyException();

            then(riderLocationRepository).should().saveIfNewer(RIDER_ID, LOCATION);
            then(trackingPublisher).should(never()).publish(anyLong(), any());
        }
    }

    @ParameterizedTest
    @EnumSource(value = OperatingStatus.class, names = {"AVAILABLE", "UNAVAILABLE"})
    @DisplayName("BUSY 가 아니면 409 로 거부하고 아무것도 건드리지 않는다")
    void rejectsNonBusyRider(OperatingStatus status) {
        assertThatThrownBy(() -> service.update(rider(status), LOCATION))
                .isInstanceOf(BusinessException.class)
                .hasMessage("배송 수행 중(BUSY)이 아닙니다.")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        then(deliveryOrderRepository).shouldHaveNoInteractions();
        then(riderLocationRepository).shouldHaveNoInteractions();
        then(trackingPublisher).shouldHaveNoInteractions();
    }
}
