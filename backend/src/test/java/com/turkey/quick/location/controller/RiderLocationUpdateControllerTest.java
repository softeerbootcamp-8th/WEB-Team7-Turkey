package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.location.sse.SseRelay;
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

/** 라이더 위치 갱신 시 배차 후보(GEO) 반영 로직(#83)만 검증한다. 운행 상태 허용 목록·401은 E2E에서 본다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("라이더 위치 갱신 — GEO 배차 후보 반영(#83)")
class RiderLocationUpdateControllerTest {

    @InjectMocks
    private RiderLocationUpdateController controller;

    @Mock
    private SseRelay sseRelay;

    @Mock
    private RiderGeoRepository riderGeoRepository;

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
    @DisplayName("GEO 갱신이 실패해도 위치 갱신 응답 자체는 성공한다")
    void succeedsEvenWhenGeoSyncFails() {
        willThrow(new RuntimeException("redis down")).given(riderGeoRepository).registerOrUpdate(RIDER_ID, LAT, LNG);
        RiderLocationUpdateRequest request = request();

        var response = controller.updateRiderLocation(request, rider(OperatingStatus.AVAILABLE));

        assertThat(response.success()).isTrue();
        then(sseRelay).should().publish(10L, request.toLocationPayload());
    }
}
