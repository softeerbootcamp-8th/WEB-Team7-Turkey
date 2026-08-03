package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 라이더 위치 갱신의 Redis 반영(GEO 배차 후보 #83, 최신 위치 저장 #317)과 팬아웃 발행만 검증한다.
 * 운행 상태 허용 목록·401은 E2E에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("라이더 위치 갱신 — Redis 반영과 팬아웃 발행")
class RiderLocationUpdateControllerTest {

    @InjectMocks
    private RiderLocationUpdateController controller;

    @Mock
    private TrackingPublisher trackingPublisher;

    @Mock
    private RiderGeoRepository riderGeoRepository;

    @Mock
    private RiderLocationRepository riderLocationRepository;

    private static final Long RIDER_ID = 1L;
    private static final BigDecimal LAT = new BigDecimal("37.5000000");
    private static final BigDecimal LNG = new BigDecimal("127.0000000");

    private AuthenticatedRider rider(OperatingStatus status) {
        return new AuthenticatedRider(RIDER_ID, "rider1", "홍라이더", status);
    }

    private RiderLocationUpdateRequest request() {
        return new RiderLocationUpdateRequest(10L, LAT, LNG, Instant.now(), null);
    }

    @Test
    @DisplayName("AVAILABLE이면 GEO에 등록·갱신한다")
    void registersWhenAvailable() {
        controller.updateRiderLocation(request(), rider(OperatingStatus.AVAILABLE));

        then(riderGeoRepository).should().registerOrUpdate(RIDER_ID, LAT, LNG);
        then(riderGeoRepository).should(never()).remove(RIDER_ID);
    }

    @Test
    @DisplayName("BUSY면 GEO 후보에서 뺀다")
    void removesWhenBusy() {
        controller.updateRiderLocation(request(), rider(OperatingStatus.BUSY));

        then(riderGeoRepository).should().remove(RIDER_ID);
        then(riderGeoRepository).should(never()).registerOrUpdate(RIDER_ID, LAT, LNG);
    }

    @Test
    @DisplayName("BUSY 라이더의 최신 위치만 저장한다")
    void savesLatestLocationOnlyWhenBusy() {
        // 두 저장소의 대상이 상태로 갈린다: 배차 후보(GEO)는 AVAILABLE, 최신 위치는 BUSY.
        // 추적 중인 고객이 보게 될 값이 이것이고, 배송을 맡지 않은 라이더에게는 읽을 사람이 없다.
        RiderLocationUpdateRequest busy = request();
        controller.updateRiderLocation(busy, rider(OperatingStatus.BUSY));

        then(riderLocationRepository).should().saveIfNewer(RIDER_ID, busy.toLocationPayload());
    }

    @Test
    @DisplayName("AVAILABLE 라이더의 최신 위치는 저장하지 않는다")
    void doesNotSaveLatestLocationWhenAvailable() {
        // else 분기에 얹으면 허용 상태가 하나 늘었을 때 조용히 저장되기 시작하므로, 명시적으로
        // BUSY 만 저장한다. 서버측 필터(#82)를 되살리면 기준선이 필요해져 이 단언이 바뀐다.
        controller.updateRiderLocation(request(), rider(OperatingStatus.AVAILABLE));

        then(riderLocationRepository).should(never()).saveIfNewer(eq(RIDER_ID), any());
    }

    @Test
    @DisplayName("배송 채널로 위치를 발행한다 — 로컬 레지스트리를 직접 부르지 않는다")
    void publishesToDeliveryChannel() {
        // 로컬 relay 를 직접 부르면 다른 인스턴스에 연결된 고객이 이벤트를 못 받는다(#317).
        RiderLocationUpdateRequest request = request();

        controller.updateRiderLocation(request, rider(OperatingStatus.BUSY));

        then(trackingPublisher).should().publish(10L, request.toLocationPayload());
    }

    @Test
    @DisplayName("Redis 반영이 실패해도 위치 갱신 응답과 팬아웃 발행은 계속된다")
    void succeedsEvenWhenRedisSyncFails() {
        willThrow(new RuntimeException("redis down")).given(riderGeoRepository).registerOrUpdate(RIDER_ID, LAT, LNG);
        RiderLocationUpdateRequest request = request();

        var response = controller.updateRiderLocation(request, rider(OperatingStatus.AVAILABLE));

        assertThat(response.success()).isTrue();
        then(trackingPublisher).should().publish(10L, request.toLocationPayload());
    }

    @Test
    @DisplayName("최신 위치 저장이 실패해도 팬아웃 발행은 계속된다")
    void publishesEvenWhenLatestLocationSaveFails() {
        // 저장은 읽는 쪽이 아직 없어(#317) 실패해도 사용자에게 보이는 영향이 없지만, 추적 중계는
        // 지금 화면에 보이는 기능이라 함께 죽어서는 안 된다.
        willThrow(new RuntimeException("NOSCRIPT")).given(riderLocationRepository)
                .saveIfNewer(eq(RIDER_ID), any());
        RiderLocationUpdateRequest request = request();

        var response = controller.updateRiderLocation(request, rider(OperatingStatus.BUSY));

        assertThat(response.success()).isTrue();
        then(trackingPublisher).should().publish(10L, request.toLocationPayload());
    }
}
