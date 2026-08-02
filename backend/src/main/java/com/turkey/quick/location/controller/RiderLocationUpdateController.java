package com.turkey.quick.location.controller;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.location.sse.SseRelay;
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

    private final SseRelay sseRelay;
    private final RiderGeoRepository riderGeoRepository;

    @Override
    public ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request, AuthenticatedRider rider) {
        if (!LOCATION_ALLOWED_STATUSES.contains(rider.operatingStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "운행 중이 아닙니다.");
        }
        syncGeoCandidate(rider, request);
        sseRelay.publish(request.deliveryId(), request.toLocationPayload());
        return ApiResponse.ok(null);
    }

    /**
     * 배차 후보 반영(#83). AVAILABLE이면 이 위치로 등록·갱신하고, BUSY면 후보에서 뺀다 — 위치
     * 전송(추적 중계용)은 BUSY에서도 계속되지만 배차 대상은 아니어야 하기 때문이다.
     *
     * <p>Redis 갱신이 실패해도 이 요청(SSE 중계) 자체는 계속 성공해야 하므로 예외를 삼키고
     * 로깅만 한다(이슈 예외 처리 조항). 실패하면 그 라이더가 배차 후보에서 빠진 채로 남는 것
     * 자체가 "잘못된 후보를 쓰지 않는다"는 안전장치이므로 별도 보정이 필요 없다.
     */
    private void syncGeoCandidate(AuthenticatedRider rider, RiderLocationUpdateRequest request) {
        try {
            if (rider.operatingStatus() == OperatingStatus.AVAILABLE) {
                riderGeoRepository.registerOrUpdate(rider.memberId(), request.latitude(), request.longitude());
            } else {
                riderGeoRepository.remove(rider.memberId());
            }
        } catch (RuntimeException e) {
            log.warn("event=GEO_CANDIDATE_SYNC_FAILED riderId={} operatingStatus={}",
                    rider.memberId(), rider.operatingStatus(), e);
        }
    }
}
