package com.turkey.quick.order.service;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배차 대기 시간 초과 자동 취소(#42)의 능동 스캐너. 매 주기 {@code delivery_order} 테이블
 * 전체에서 만료된 WAITING 행을 찾아 한 건씩 취소·환급한다.
 *
 * <p><b>{@link DeliveryTimeoutService} 와 다른 빈으로 분리한 이유</b>: 이 메서드 자체에는
 * {@code @Transactional} 을 두지 않는다 — 주문 하나의 실패가 나머지 취소 처리를 막으면 안 되기
 * 때문이다. 그래서 {@code cancelAndRefund} 를 주문마다 <b>다른 빈의 프록시를 통해</b> 호출해야
 * 그 메서드의 {@code @Transactional}이 실제로 적용되어 각 주문이 독립된 트랜잭션으로 처리된다
 * (자기 호출은 프록시를 타지 않는다).
 *
 * <p><b>다중 인스턴스 분산 락은 아직 두지 않는다</b>(사람 확인, 2026-08-04). 조건부 UPDATE가
 * 정합성을 보장하므로 인스턴스가 여러 대라도 같은 주문이 중복 취소·중복 환급되지 않는다 —
 * 두 번째 인스턴스의 {@code cancelIfWaiting} 은 0 행을 받고 조용히 넘어간다. 인스턴스가 늘어나면
 * 같은 스캔을 반복하는 비용만 커지는데, 지금(단일 인스턴스 운영)은 그 비용이 없다. 실제로 여러
 * 인스턴스로 확장되는 시점에 Redis 기반 락(예: {@code SET NX PX})을 재검토한다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryOrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrderExpiryScheduler.class);

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final DeliveryTimeoutService deliveryTimeoutService;

    /** 스캔 주기(사람 확인, 2026-08-04). 최대 이 주기만큼 타임아웃 감지가 늦을 수 있다(폴링 특성). */
    @Scheduled(fixedRate = 60_000)
    public void cancelExpiredWaitingOrders() {
        LocalDateTime cutoff = DeliveryTimeoutService.cutoff(LocalDateTime.now(ZoneOffset.UTC));
        List<DeliveryOrder> expired =
                deliveryOrderRepository.findByStatusAndRequestedAtBefore(OrderStatus.WAITING, cutoff);
        if (expired.isEmpty()) {
            return;
        }

        int canceled = 0;
        for (DeliveryOrder order : expired) {
            try {
                // customer 는 LAZY 프록시지만 식별자 접근은 조회를 유발하지 않는다
                // (CustomerPaymentService.cancelPointCharge 와 같은 근거).
                if (deliveryTimeoutService.cancelAndRefund(order.getId(), order.getCustomer().getId())) {
                    canceled++;
                }
            } catch (RuntimeException e) {
                // 한 건의 실패가 나머지 취소 처리를 막지 않는다. 다음 주기에 다시 시도된다.
                log.error("event=DELIVERY_TIMEOUT_CANCEL_FAILED orderId={}", order.getId(), e);
            }
        }
        log.info("[배차대기-자동취소-스캔] 대상={}건, 처리={}건", expired.size(), canceled);
    }
}
