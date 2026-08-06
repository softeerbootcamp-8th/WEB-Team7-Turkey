package com.turkey.quick.order.service;

import com.turkey.quick.order.dto.ActiveDeliveryResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 홈의 진행 중 배송 요약 조회(#100). 고객당 진행 중(WAITING~DELIVERING) 배송은 최대 1건이라
 * 결과는 0건 또는 1건이다.
 *
 * <p>"진행 중"의 정의를 여기서 다시 나열하지 않는다 — {@link DeliveryOrderRepository#findActiveByCustomerId}
 * 가 {@code active_customer_id} 생성 컬럼(UNIQUE)을 읽어 그 정의와 ≤1건 보장을 DB 스키마에 위임한다
 * (#42 지연 만료가 쓰던 것과 같은 조회). 진행 중 배송이 없으면 {@code null} 을 반환하고, 컨트롤러가
 * {@code ApiResponse.ok(null)} 로 감싸 이슈 #100 의 "빈 결과"를 표현한다.
 */
@Service
@RequiredArgsConstructor
public class ActiveDeliveryQueryService {

    private final DeliveryOrderRepository deliveryOrderRepository;

    @Transactional(readOnly = true)
    public ActiveDeliveryResponse getActiveDelivery(Long customerId) {
        return deliveryOrderRepository.findActiveByCustomerId(customerId)
                .map(ActiveDeliveryResponse::from)
                .orElse(null);
    }
}
