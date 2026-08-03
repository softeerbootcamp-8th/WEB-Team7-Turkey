package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryService {

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;

    /** 배정된 라이더가 픽업지로 출발하며, 라이더의 BUSY 상태는 변경하지 않는다. */
    @Transactional
    public RiderDeliveryResponse transition(AuthenticatedRider rider, Long deliveryId,
                                            RiderDeliveryAction action) {
        if (rider.operatingStatus() != OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "배송 수행 중인 라이더만 배송 단계를 변경할 수 있습니다.");
        }
        if (action != RiderDeliveryAction.START_MOVING_TO_PICKUP) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "현재 구현되지 않은 배송 전이 행위입니다. action=" + action);
        }

        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = deliveryOrderRepository.startMovingToPickupIfAssigned(
                deliveryId, rider.memberId(), startedAt);
        if (updated == 0) {
            throw transitionFailure(deliveryId, rider.memberId());
        }

        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 상태를 변경한 주문을 다시 조회할 수 없습니다. orderId=" + deliveryId));
        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(deliveryId, FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "배차된 주문의 예상 운임 스냅샷이 없습니다. orderId=" + deliveryId));

        log.info("event=RIDER_DELIVERY_STATUS_CHANGED riderId={} orderId={} previousStatus={} newStatus={}",
                rider.memberId(), deliveryId, OrderStatus.ASSIGNED, OrderStatus.MOVING_TO_PICKUP);
        return RiderDeliveryResponse.from(order, estimate.getTotalFare());
    }

    private BusinessException transitionFailure(Long deliveryId, Long riderId) {
        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "배송요청을 찾을 수 없습니다. deliveryId=" + deliveryId));

        if (order.getAssignedRider() == null
                || !order.getAssignedRider().getMemberId().equals(riderId)) {
            return new BusinessException(HttpStatus.FORBIDDEN,
                    "해당 배송에 배정된 라이더만 상태를 변경할 수 있습니다.");
        }
        return new BusinessException(HttpStatus.CONFLICT,
                "픽업지 이동을 시작할 수 없는 배송 상태입니다. status=" + order.getStatus());
    }
}
