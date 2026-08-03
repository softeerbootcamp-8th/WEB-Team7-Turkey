package com.turkey.quick.location.controller;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RiderLocationUpdateController implements RiderLocationUpdateApi {

    // 허용 목록으로 두는 이유: 나중에 상태가 하나 늘었을 때 거부가 아니라 허용으로 조용히
    // 새는 쪽이 더 위험하다(RiderLocationService의 옛 판단과 동일).
    private static final Set<OperatingStatus> LOCATION_ALLOWED_STATUSES =
            EnumSet.of(OperatingStatus.AVAILABLE, OperatingStatus.BUSY);

    private final TrackingPublisher trackingPublisher;
    private final RiderGeoRepository riderGeoRepository;
    private final RiderLocationRepository riderLocationRepository;

    @Override
    public ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request, AuthenticatedRider rider) {
        if (!LOCATION_ALLOWED_STATUSES.contains(rider.operatingStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "운행 중이 아닙니다.");
        }
        syncRedisState(rider, request);
        // 팬아웃(#317). 로컬 레지스트리를 직접 부르지 않는 것이 핵심이다 — 고객은 다른 인스턴스에
        // 연결돼 있을 수 있다. TrackingPublisher 는 스스로 예외를 삼키므로 여기서 감싸지 않는다.
        trackingPublisher.publish(request.deliveryId(), request.toLocationPayload());
        return ApiResponse.ok(null);
    }

    /**
     * 배차 후보 반영(#83)과 최신 위치 저장(#317). 둘 다 Redis 쓰기이고 실패 처리가 같아 한 번에 묶는다.
     *
     * <p>AVAILABLE이면 이 위치로 배차 후보를 등록·갱신하고, BUSY면 후보에서 뺀다 — 위치
     * 전송(추적 중계용)은 BUSY에서도 계속되지만 배차 대상은 아니어야 하기 때문이다.
     * 최신 위치는 운행 상태와 무관하게 저장한다.
     *
     * <p>Redis 갱신이 실패해도 이 요청(SSE 중계) 자체는 계속 성공해야 하므로 예외를 삼키고
     * 로깅만 한다(이슈 예외 처리 조항). 배차 후보는 실패하면 그 라이더가 후보에서 빠진 채로 남는 것
     * 자체가 "잘못된 후보를 쓰지 않는다"는 안전장치이고, 최신 위치는 다음 전송(BUSY 5초 주기)이
     * 복구한다.
     */
    private void syncRedisState(AuthenticatedRider rider, RiderLocationUpdateRequest request) {
        try {
            if (rider.operatingStatus() == OperatingStatus.AVAILABLE) {
                riderGeoRepository.registerOrUpdate(rider.memberId(), request.latitude(), request.longitude());
            } else {
                riderGeoRepository.remove(rider.memberId());
            }
            riderLocationRepository.saveIfNewer(rider.memberId(), request.toLocationPayload());
        } catch (RuntimeException e) {
            log.warn("event=RIDER_LOCATION_REDIS_SYNC_FAILED riderId={} operatingStatus={}",
                    rider.memberId(), rider.operatingStatus(), e);
        }
    }
}
