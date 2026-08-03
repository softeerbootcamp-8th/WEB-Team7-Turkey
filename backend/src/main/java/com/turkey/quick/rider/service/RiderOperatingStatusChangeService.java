package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderOperatingAction;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 운행 상태 변경(#54) — 콜 받기(GO_ONLINE)/운행 종료(GO_OFFLINE).
 *
 * <p>목표 상태를 받지 않고 <b>행위</b>를 받는다(팀 규칙: 상태는 요청 값으로 덮어쓰지 않고 현재 상태 +
 * 수행 행위로 검증한다). BUSY 로/에서의 전이는 이 API 에 없다 — 배차 확정과 배송 완료의 부수 효과로만
 * 일어난다. 그래서 BUSY 라이더의 직접 변경 요청은 전이 시도 전에 409 로 거부한다.
 *
 * <p>멱등: 이미 목표 상태면 전이 없이 현재 상태를 그대로 돌려준다(#54 예외 처리). 도메인
 * {@code goOnline/goOffline} 은 현재 상태가 정확히 출발 상태가 아니면 {@code IllegalStateException}
 * 을 던지므로(그건 핸들러에서 400 이 된다), 같은 상태 재요청을 도메인까지 보내지 않고 여기서 흡수한다.
 *
 * <p>운행 종료 시 GEO 배차 후보에서 즉시 뺀다({@link RiderGeoRepository#remove}, #83). 상태 전이
 * (AVAILABLE→UNAVAILABLE)만으로도 배차 후보 조회에서 빠지지만, 좌표가 남아 위치 기반 검색에
 * 잡히는 틈(#81)을 그 remove 가 닫는다. 커밋 전에 지우므로, 커밋이 실패하면 상태는 AVAILABLE 로
 * 롤백되고 위치만 없는 상태가 되는데 — 그 라이더는 다음 위치 전송 전까지 위치 기반 검색에 잡히지
 * 않으니 "종료 의도"와 어긋나지 않는다. Redis 실패는 다른 GEO 반영 지점(#83)과 같은 이유로
 * 로깅만 하고 상태 전이 자체는 그대로 커밋한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderOperatingStatusChangeService {

    private final RiderProfileRepository riderProfileRepository;
    private final RiderGeoRepository riderGeoRepository;
    private final DeliveryOrderRepository deliveryOrderRepository;

    @Transactional
    public RiderOperatingStatusResponse changeOperatingStatus(Long riderId, RiderOperatingAction action) {
        RiderProfile profile = riderProfileRepository.findById(riderId).orElseThrow();
        OperatingStatus current = profile.getOperatingStatus();
        boolean hasInProgressDelivery = deliveryOrderRepository
                .existsByAssignedRider_MemberIdAndStatusIn(riderId, OrderStatus.trackableStatuses());

        if (current == OperatingStatus.BUSY) {
            if (!hasInProgressDelivery) {
                log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} reason=BUSY_WITHOUT_DELIVERY",
                        riderId);
            }
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 진행 중에는 운행 상태를 직접 변경할 수 없습니다.");
        }
        if (hasInProgressDelivery) {
            log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} reason=DELIVERY_WITHOUT_BUSY status={}",
                    riderId, current);
            throw new BusinessException(HttpStatus.CONFLICT,
                    "진행 중 배송 정보와 라이더 상태가 일치하지 않아 상태를 변경할 수 없습니다.");
        }

        OperatingStatus target = switch (action) {
            case GO_ONLINE -> OperatingStatus.AVAILABLE;
            case GO_OFFLINE -> OperatingStatus.UNAVAILABLE;
        };
        if (current == target) {
            return toResponse(profile); // 멱등: 같은 상태 재요청은 오류 없이 현재 상태 반환
        }

        switch (action) {
            case GO_ONLINE -> profile.goOnline();   // UNAVAILABLE → AVAILABLE
            case GO_OFFLINE -> {
                profile.goOffline(); // AVAILABLE → UNAVAILABLE
                try {
                    riderGeoRepository.remove(riderId); // 배차 후보에서 즉시 제외(#81 틈 닫기)
                } catch (RuntimeException e) {
                    log.warn("event=GEO_CANDIDATE_SYNC_FAILED riderId={} reason=GO_OFFLINE", riderId, e);
                }
            }
        }
        return toResponse(profile);
    }

    /** 콜 받기/운행 종료 결과는 AVAILABLE/UNAVAILABLE 이므로 진행 중 배송은 항상 없다 → currentDeliveryId=null. */
    private RiderOperatingStatusResponse toResponse(RiderProfile profile) {
        return new RiderOperatingStatusResponse(
                profile.getOperatingStatus(), profile.getStatusChangedAt(), null);
    }
}
