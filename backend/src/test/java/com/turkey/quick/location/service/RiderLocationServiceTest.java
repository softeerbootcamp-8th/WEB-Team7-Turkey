package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderGeoRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 운행 상태가 하는 일을 가른다: AVAILABLE 은 GEO 배차 후보만, BUSY 는 최신 위치 저장 + 추적 발행.
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
    private RiderGeoRepository riderGeoRepository;

    @Mock
    private RiderLocationRepository riderLocationRepository;

    @Mock
    private TrackingPublisher trackingPublisher;

    private AuthenticatedRider rider(OperatingStatus status) {
        return new AuthenticatedRider(RIDER_ID, "rider1", "홍라이더", status);
    }

    private void givenInProgressDelivery() {
        given(deliveryOrderRepository.findInProgressIdByActiveRiderId(RIDER_ID))
                .willReturn(Optional.of(DELIVERY_ID));
    }

    @Nested
    @DisplayName("AVAILABLE")
    class Available {

        @Test
        @DisplayName("GEO 배차 후보로 등록·갱신한다")
        void registersGeoCandidate() {
            service.update(rider(OperatingStatus.AVAILABLE), LOCATION);

            then(riderGeoRepository).should()
                    .registerOrUpdate(RIDER_ID, LOCATION.latitude(), LOCATION.longitude());
            then(riderGeoRepository).should(never()).remove(RIDER_ID);
        }

        @Test
        @DisplayName("최신 위치를 저장하지 않는다")
        void doesNotSaveLatestLocation() {
            // else 분기에 얹으면 허용 상태가 하나 늘었을 때 조용히 저장되기 시작하므로 명시적으로
            // BUSY 만 저장한다. 서버측 필터(#82)를 되살리면 기준선이 필요해져 이 단언이 바뀐다.
            service.update(rider(OperatingStatus.AVAILABLE), LOCATION);

            then(riderLocationRepository).should(never()).saveIfNewer(eq(RIDER_ID), any());
        }

        @Test
        @DisplayName("배송을 조회하지도, 발행하지도 않는다")
        void neitherLooksUpDeliveryNorPublishes() {
            // 배송을 맡지 않은 라이더를 추적하는 고객은 존재할 수 없다. 조회 자체를 하지 않는 것이
            // 위치 갱신 핫패스에서 불필요한 MySQL 왕복을 없앤다.
            service.update(rider(OperatingStatus.AVAILABLE), LOCATION);

            then(deliveryOrderRepository).should(never()).findInProgressIdByActiveRiderId(anyLong());
            then(trackingPublisher).should(never()).publish(anyLong(), any());
        }
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

            then(trackingPublisher).should().publish(DELIVERY_ID, LOCATION);
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

            then(deliveryOrderRepository).should().findInProgressIdByActiveRiderId(RIDER_ID);
            then(deliveryOrderRepository).should(never()).findInProgressByRiderId(anyLong(), anySet());
        }

        @Test
        @DisplayName("GEO 배차 후보에서 뺀다")
        void removesGeoCandidate() {
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(riderGeoRepository).should().remove(RIDER_ID);
            then(riderGeoRepository).should(never())
                    .registerOrUpdate(anyLong(), any(), any());
        }

        @Test
        @DisplayName("최신 위치를 저장한다")
        void savesLatestLocation() {
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(riderLocationRepository).should().saveIfNewer(RIDER_ID, LOCATION);
        }

        @Test
        @DisplayName("수행 중 배송이 없으면 발행하지 않고 조용히 끝낸다")
        void skipsPublishWhenNoInProgressDelivery() {
            // 운행 상태는 BUSY 인데 배정된 배송이 없는 정합성 깨진 상태다. 위치 갱신 자체는
            // 실패시키지 않고(로그로 드러낸다) 발행만 건너뛴다.
            given(deliveryOrderRepository.findInProgressIdByActiveRiderId(RIDER_ID))
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
        @DisplayName("Redis 반영이 실패해도 발행은 계속된다")
        void publishesEvenWhenRedisSyncFails() {
            willThrow(new RuntimeException("redis down")).given(riderGeoRepository).remove(RIDER_ID);
            givenInProgressDelivery();

            service.update(rider(OperatingStatus.BUSY), LOCATION);

            then(trackingPublisher).should().publish(DELIVERY_ID, LOCATION);
        }

        @Test
        @DisplayName("배송 조회(MySQL)가 실패해도 위치 갱신은 성공한다")
        void survivesDatabaseFailure() {
            // 이 경로에 MySQL 의존이 새로 생겼다. DB 장애가 배차 후보 갱신(#83)과 최신 위치 저장까지
            // 같이 죽이면 안 된다 — 전달은 at-most-once 이고 다음 전송(5초)이 복구한다.
            willThrow(new RuntimeException("mysql down")).given(deliveryOrderRepository)
                    .findInProgressIdByActiveRiderId(RIDER_ID);

            assertThatCode(() -> service.update(rider(OperatingStatus.BUSY), LOCATION))
                    .doesNotThrowAnyException();

            then(riderLocationRepository).should().saveIfNewer(RIDER_ID, LOCATION);
            then(trackingPublisher).should(never()).publish(anyLong(), any());
        }
    }

    @Test
    @DisplayName("UNAVAILABLE 이면 409 로 거부하고 아무것도 건드리지 않는다")
    void rejectsUnavailableRider() {
        assertThatThrownBy(() -> service.update(rider(OperatingStatus.UNAVAILABLE), LOCATION))
                .isInstanceOf(BusinessException.class)
                .hasMessage("운행 중이 아닙니다.")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        then(riderGeoRepository).shouldHaveNoInteractions();
        then(riderLocationRepository).shouldHaveNoInteractions();
        then(trackingPublisher).shouldHaveNoInteractions();
    }
}
