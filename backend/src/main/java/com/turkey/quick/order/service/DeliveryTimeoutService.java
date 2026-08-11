package com.turkey.quick.order.service;

import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.service.CustomerPaymentService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배차 대기 시간 초과 자동 취소(REQ-ORD-004, #42).
 *
 * <p><b>Redis GEO 와 무관하다.</b> #41(주변 라이더 탐색)·#101(검색 반경 단계적 확대)이
 * discussion #338 로 전부 폐기되면서, 이 기능은 순수하게 "WAITING 으로 {@link #WAITING_TIMEOUT}
 * 이상 머문 주문을 취소하고 환불한다"는 시간 기반 판정만 남았다. 반경·후보 라이더 존재 여부는
 * 이 판정에 관여하지 않는다.
 *
 * <p>능동적 스캐너({@link DeliveryOrderExpiryScheduler})와 지연 만료({@link #expireIfStale})
 * 둘을 함께 둔다 — Redis 키가 TTL 로 자연 소멸하는 것과 같은 하이브리드다. 스캐너는 최대
 * 스캔 주기만큼 감지가 늦을 수 있는데(폴링 기반의 고유한 특성), 그 창 안에서 고객이 새 주문을
 * 만들려 하면 {@code active_customer_id} UNIQUE 제약이 여전히 막는다 — 지연 만료가 그 순간에
 * 만료된 주문을 먼저 정리해 창을 없앤다.
 */
@RequiredArgsConstructor
@Service
public class DeliveryTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTimeoutService.class);

    /** 배차 대기 타임아웃(사람 확인, 2026-08-04). */
    static final Duration WAITING_TIMEOUT = Duration.ofMinutes(5);

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final CustomerPaymentService customerPaymentService;

    /** CANCELED 전이를 그 배송의 SSE 채널에 알린다(#444). 트랜잭션 안에서 부르면 커밋 후로 자동으로 미뤄진다. */
    /** #450: 자동 취소된 배송의 SSE 연결을 정리하기 위해 주입한다. */
    private final TrackingPublisher trackingPublisher;

    /**
     * 지연 만료(#42 비고 + 사람 확인). 주문 생성 흐름({@link DeliveryService#createDelivery}) 맨 앞에서
     * 호출한다 — 고객의 기존 진행 중 주문이 타임아웃을 이미 넘긴 WAITING 이면, 스캐너를 기다리지 않고
     * 그 자리에서 먼저 취소·환불한 뒤 새 주문 생성으로 넘어간다.
     *
     * <p><b>{@code Propagation.REQUIRES_NEW}</b>: 호출자({@code createDelivery})의 트랜잭션과 분리된
     * 별도 트랜잭션으로 커밋한다. 이 취소가 성립한 뒤 새 주문 생성이 다른 이유(요금 변경 등)로 실패해도,
     * 방금 처리한 이 만료 취소·환급까지 함께 롤백될 이유가 없다 — 그건 새 주문 시도와 무관하게 이미
     * 옳은 정리다. 다른 빈({@code DeliveryService})에서 호출해야 프록시가 이 전파 속성을 인식한다.
     *
     * @return 실제로 만료 처리했는지. false 면 진행 중 주문이 없거나, 있어도 아직 타임아웃 전이거나
     *         WAITING 이 아니라는 뜻이다(정상 흐름).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfStale(Long customerId) {
        return deliveryOrderRepository.findActiveByCustomerId(customerId)
                .filter(this::isWaitingAndExpired)
                .map(order -> cancelAndRefund(order.getId(), customerId))
                .orElse(false);
    }

    /**
     * 배차 수락(#56)에서도 같은 원칙을 적용하기 위해 노출한다 — 스캐너가 아직 훑지 않은 만료 주문을
     * 라이더가 여전히 수락할 수 있는 창을 없앤다. 실제로 만료 처리를 했다면 이후 그 주문에 대한
     * 조건부 UPDATE(예: {@code assignIfWaiting})는 자연히 0 행이 되어, 호출부의 기존 "이미
     * 취소됨" 처리 경로로 그대로 흘러간다 — 별도 예외 타입을 새로 만들지 않는다.
     *
     * <p>{@code Propagation.REQUIRES_NEW}: {@link #expireIfStale} 과 같은 이유로, 이 정리는
     * 호출자(배차 수락 시도)의 성공·실패와 무관하게 독립적으로 커밋돼야 한다.
     *
     * @return 실제로 만료 처리했는지. false 면 주문이 없거나, WAITING 이 아니거나, 아직 타임아웃
     *         전이라는 뜻이다(정상 흐름).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelIfExpired(Long orderId) {
        return deliveryOrderRepository.findById(orderId)
                .filter(this::isWaitingAndExpired)
                // customer 는 LAZY 프록시지만 식별자 접근은 조회를 유발하지 않는다
                // (CustomerPaymentService.cancelPointCharge 와 같은 근거).
                .map(order -> cancelAndRefund(order.getId(), order.getCustomer().getId()))
                .orElse(false);
    }

    private boolean isWaitingAndExpired(DeliveryOrder order) {
        return order.getStatus() == OrderStatus.WAITING && isExpired(order.getRequestedAt());
    }

    /**
     * 취소 + 환급을 한 트랜잭션에 묶는다(#42 비고, #38 비고 — 둘 다 "하나의 상위 트랜잭션"을 요구).
     *
     * <p>다른 빈에서 호출될 때는(스캐너) 이 메서드 자체가 트랜잭션 경계가 되고, 같은 빈 안에서
     * 호출될 때는({@link #expireIfStale}) 이미 열려 있는 {@code REQUIRES_NEW} 트랜잭션 안에서 평범한
     * 메서드 호출로 실행된다(자기 호출은 프록시를 타지 않지만, 그 경우엔 이미 트랜잭션이 열려 있어
     * 문제되지 않는다).
     *
     * @return 실제로 취소했는지. false 면 조건부 UPDATE 가 0 행이었다는 뜻 — 그 사이 배차가
     *         확정됐거나(#56) 이미 취소된 것이며, 둘 다 정상 흐름이라 예외를 던지지 않는다.
     */
    @Transactional
    public boolean cancelAndRefund(Long orderId, Long customerId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = deliveryOrderRepository.cancelIfWaiting(orderId, now,
                "배차 대기 시간을 초과해 자동 취소되었습니다.");
        if (updated == 0) {
            log.info("[배차대기-자동취소-스킵] orderId={}, reason={}", orderId, "이미 배차되었거나 취소됨");
            return false;
        }

        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(orderId, FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "배송요청에 예상 운임 스냅샷이 없습니다. orderId=" + orderId));

        // FK 를 채우는 것이 목적이라 프록시 참조로 충분하다(createDelivery 와 같은 판단).
        DeliveryOrder orderRef = deliveryOrderRepository.getReferenceById(orderId);
        long balanceAfter = customerPaymentService.refundForCancel(
                customerId, orderRef, estimate.getTotalFare());

        trackingPublisher.publishStatus(orderId, OrderStatus.CANCELED, now.toInstant(ZoneOffset.UTC));
        // 취소되면 이 배송으로는 더 보낼 것이 없으므로 연결을 정리한다(#450). 화면 정합성은
        // 클라이언트 타이머(#444)가 담당하고, 여기서는 고객이 화면을 떠난 경우에도 연결이
        // emitter 절대 수명(5분)까지 남지 않게 하는 것이 목적이다.
        //
        // 수동 취소(DeliveryService.cancelDelivery)에는 붙이지 않는다 — 고객이 직접 호출해
        // 응답을 받고 화면이 재조회하면 구독 조건이 꺼져 훅이 클라이언트 쪽에서 닫는다.
        trackingPublisher.publishClose(orderId);

        log.info("[배차대기-자동취소] orderId={}, customerId={}, refundAmount={}, balanceAfter={}",
                orderId, customerId, estimate.getTotalFare(), balanceAfter);
        return true;
    }

    private boolean isExpired(LocalDateTime requestedAt) {
        return requestedAt.isBefore(LocalDateTime.now(ZoneOffset.UTC).minus(WAITING_TIMEOUT));
    }

    static LocalDateTime cutoff(LocalDateTime now) {
        return now.minus(WAITING_TIMEOUT);
    }
}
