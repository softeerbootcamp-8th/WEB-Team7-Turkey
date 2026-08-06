package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
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
 * <p>운행 종료(GO_OFFLINE)는 상태 전이(AVAILABLE→UNAVAILABLE)만 한다. 예전에는 여기서 GEO 배차
 * 후보({@code riders:geo})에서도 즉시 뺐지만(#83), 배차 위치 검색을 라이더가 아니라 주문 픽업지
 * 인덱싱으로 뒤집으면서(#101 미구현) 라이더-측 GEO 사용처를 전부 제거했다(#342). 이제 라이더를
 * GEO 에 넣는 곳 자체가 없으므로 운행 종료 시 지울 좌표도 없다 — 배차 후보 자격은 주문 상태와
 * 라이더 {@code operating_status} 로만 판정된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderOperatingStatusChangeService {

    private final RiderProfileRepository riderProfileRepository;
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
            case GO_ONLINE -> profile.goOnline();    // UNAVAILABLE → AVAILABLE
            case GO_OFFLINE -> profile.goOffline();   // AVAILABLE → UNAVAILABLE
        }
        return toResponse(profile);
    }

    /** 콜 받기/운행 종료 결과는 AVAILABLE/UNAVAILABLE 이므로 진행 중 배송은 항상 없다 → currentDeliveryId=null. */
    private RiderOperatingStatusResponse toResponse(RiderProfile profile) {
        return new RiderOperatingStatusResponse(
                profile.getOperatingStatus(), profile.getStatusChangedAt(), null);
    }
}
