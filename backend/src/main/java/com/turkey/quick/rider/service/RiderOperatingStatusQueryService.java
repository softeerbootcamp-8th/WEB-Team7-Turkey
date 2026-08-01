package com.turkey.quick.rider.service;

import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 홈 화면 조회(#53)의 읽기 전용 서비스.
 *
 * <p>홈 화면은 운행 상태와 "진행 중 배송이 있는지"를 함께 필요로 한다(이슈 처리 흐름 ②③). 세션
 * 인터셉터가 넘겨 준 {@code AuthenticatedRider} 에도 운행 상태는 들어 있지만 상태 변경 시각과 진행 중
 * 배송 식별자는 없어서, 여기서 프로필과 주문을 다시 조회해 한 응답으로 합친다.
 *
 * <p>진행 중 배송은 {@link DeliveryOrderRepository#findInProgressByRiderId}(#78 이 도입)를 그대로
 * 재사용한다 — 같은 "라이더의 진행 중 배송 1건"을 찾는 조회이고, 상태 집합도 같은
 * {@link OrderStatus#trackableStatuses()}(active_rider_id 생성 컬럼과 일치)를 쓴다. 같은 개념의
 * 조회·상태 목록을 다시 만들면 둘이 갈려 "BUSY 인데 진행 배송이 안 잡히는" 버그가 생긴다.
 *
 * <p>상태로 먼저 분기하지 않는 이유: trackable 상태가 아닌 라이더는 조회가 자연히 빈 결과가 되어
 * currentDeliveryId 가 null 이 된다({@link RiderOperatingStatusResponse} 계약). "BUSY 인데 진행
 * 배송이 없다" 같은 불일치가 있으면 그것이 응답에 그대로 드러나는 편이 낫다.
 */
@Service
@RequiredArgsConstructor
public class RiderOperatingStatusQueryService {

    private final RiderProfileRepository riderProfileRepository;
    private final DeliveryOrderRepository deliveryOrderRepository;

    @Transactional(readOnly = true)
    public RiderOperatingStatusResponse getOperatingStatus(Long riderId) {
        RiderProfile profile = riderProfileRepository.findById(riderId).orElseThrow();
        Long currentDeliveryId = deliveryOrderRepository
                .findInProgressByRiderId(riderId, OrderStatus.trackableStatuses())
                .map(TrackableDelivery::orderId)
                .orElse(null);
        return new RiderOperatingStatusResponse(
                profile.getOperatingStatus(), profile.getStatusChangedAt(), currentDeliveryId);
    }
}
