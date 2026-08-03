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
     * <p><b>두 저장소의 대상이 운행 상태로 갈린다.</b> 배차 후보(GEO)는 AVAILABLE 라이더의 집합이고
     * (BUSY면 후보에서 뺀다 — 위치 전송은 BUSY에서도 계속되지만 배차 대상은 아니어야 한다),
     * 최신 위치는 BUSY 라이더 — 지금 고객이 추적하고 있을 수 있는 라이더 — 만 저장한다.
     * AVAILABLE 라이더의 최신 위치를 저장하지 않는 이유는 <b>읽을 사람이 없어서</b>다: 배송을 맡지
     * 않은 라이더에게는 추적 중인 고객이 없다. 예전 구현이 상태와 무관하게 저장한 것은 서버측
     * 위치 필터(#82)의 기준선이 필요했기 때문이고, 그 필터를 되살리면 여기도 함께 되돌려야 한다.
     *
     * <p><b>최신 위치 저장을 {@code else} 에 얹지 않은 이유</b>는 {@code LOCATION_ALLOWED_STATUSES}
     * 를 허용 목록으로 둔 것과 같다 — {@code else} 는 "BUSY"가 아니라 "AVAILABLE이 아님"이고, 그게
     * BUSY로 좁혀지는 근거는 이 메서드가 아니라 호출자의 가드에 있다. 허용 상태가 하나 늘면 그
     * 상태의 위치가 <b>조용히 저장되기 시작한다.</b> 반면 GEO 쪽 {@code else} 는 그대로 둔다 —
     * {@code remove} 는 멱등이고 "후보에서 뺀다"는 새 상태가 흘러들어도 항상 안전한 방향이다.
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
            if (rider.operatingStatus() == OperatingStatus.BUSY) {
                riderLocationRepository.saveIfNewer(rider.memberId(), request.toLocationPayload());
            }
        } catch (RuntimeException e) {
            log.warn("event=RIDER_LOCATION_REDIS_SYNC_FAILED riderId={} operatingStatus={}",
                    rider.memberId(), rider.operatingStatus(), e);
        }
    }
}
