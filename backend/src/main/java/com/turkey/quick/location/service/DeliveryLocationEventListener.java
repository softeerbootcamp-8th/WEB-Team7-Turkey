package com.turkey.quick.location.service;

import com.turkey.quick.location.repository.OrderGeoRepository;
import com.turkey.quick.order.dto.DeliveryCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 배송요청 생성이 커밋된 뒤 픽업 좌표를 배차 후보 GEO({@link OrderGeoRepository})에 등록한다.
 *
 * <p>{@code AFTER_COMMIT}인 이유: 주문 생성 트랜잭션이 롤백되면(요금 변경·포인트 부족 등) 이 리스너
 * 자체가 실행되지 않으므로, 존재하지 않는 주문이 GEO에 남는 불일치가 구조적으로 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryLocationEventListener {

    private final OrderGeoRepository orderGeoRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(DeliveryCreatedEvent event) {
        orderGeoRepository.registerOrUpdate(
                event.deliveryId(), event.pickupLatitude(), event.pickupLongitude());
    }
}
