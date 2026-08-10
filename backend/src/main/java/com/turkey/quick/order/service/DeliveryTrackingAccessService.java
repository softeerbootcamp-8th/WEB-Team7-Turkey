package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객이 특정 배송의 실시간 위치를 구독할 자격이 있는지 판정한다(#77 흐름 ②).
 *
 * <p>SSE 쪽({@code location.sse})이 아니라 {@code order} 패키지에 있는 이유: 판정 대상이
 * 주문의 소유권과 상태이고, 그 규칙이 바뀌면 이 파일이 바뀌어야 한다. 그리고 위치 도메인이
 * 주문 리포지토리를 직접 부르지 않게 경계를 남긴다.
 *
 * <p><b>이 클래스가 트랜잭션 경계다.</b> 호출자(SSE 구독 서비스)는 트랜잭션 없이 돌아야 한다 —
 * {@code SseEmitter} 는 수 분간 열려 있으므로 그 생성이 트랜잭션 안에 들어가면 DB 커넥션을
 * 그만큼 붙잡는 코드가 끼어들 여지가 생긴다. 그래서 "판정은 여기서(트랜잭션 안), emitter 생성은
 * 반환 후(트랜잭션 밖)"로 순서를 고정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTrackingAccessService {

    /**
     * 존재하지 않는 주문과 <b>타인의 주문</b>에 같은 메시지를 쓴다. 사유를 나누면 남의 주문 id 를
     * 찍어 보는 것만으로 "그 주문은 존재한다"를 알 수 있고, {@code order_id} 가
     * {@code AUTO_INCREMENT} 라 열거가 쉽다. 세션 인터셉터가 실패 사유를 구분하지 않는 것과 같은 판단이다.
     */
    private static final String NOT_FOUND_MESSAGE = "배송요청을 찾을 수 없습니다.";

    private final DeliveryOrderRepository deliveryOrderRepository;

    /**
     * @param deliveryId 구독하려는 배송요청 식별자
     * @param customerId 세션에서 얻은 고객 식별자(= member_id)
     * @return 구독 가능한 배송. {@code riderId} 는 non-null 이 보장된다 — 추적 가능 상태에서는
     *         DDL {@code ck_delivery_assignment} 가 라이더 존재를 강제한다
     * @throws BusinessException 주문이 없거나 타인의 것이면 404, 추적 가능 상태가 아니면 409
     */
    @Transactional(readOnly = true)
    public TrackableDelivery authorizeTracking(Long deliveryId, Long customerId) {
        TrackableDelivery delivery = deliveryOrderRepository
                .findTrackableByIdAndCustomerId(deliveryId, customerId)
                .orElseThrow(() -> {
                    // 남의 주문을 지목한 경우도 여기로 온다. 정상 사용에서는 나오지 않는 요청이라 warn 이다.
                    log.warn("event=TRACKING_SUBSCRIBE_REJECTED deliveryId={} customerId={} reason={}",
                            deliveryId, customerId, "NOT_FOUND");
                    return new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
                });

        if (delivery.status().isTerminal()) {
            // 배송이 끝난 뒤 화면에 남아 있던 탭이 재연결하면 정상적으로 발생한다 — 그래서 info 다.
            log.info("event=TRACKING_SUBSCRIBE_REJECTED deliveryId={} customerId={} reason={} status={}",
                    deliveryId, customerId, "TERMINAL", delivery.status());
            throw new BusinessException(HttpStatus.CONFLICT, notTrackableMessage(delivery.status()));
        }
        return delivery;
    }

    /**
     * 상태 코드는 둘 다 409 지만 메시지를 나눈다. 프론트가 코드로 분기할 수는 없지만(브라우저
     * {@code EventSource} 는 응답 본문을 스크립트에 노출하지 않는다) 로그와 수동 확인에서
     * "왜 거부됐는지"를 즉시 알 수 있어야 한다.
     *
     * <p>{@code WAITING}은 더 이상 여기 오지 않는다(#401) — 라이더 배정 전에도 상태 전이 SSE를
     * 받을 수 있도록 구독을 허용했다. 위치를 보여줄 수 없는 것과 구독할 수 없는 것은 다른 질문이다.
     */
    private static String notTrackableMessage(OrderStatus status) {
        return switch (status) {
            case COMPLETED -> "완료된 배송은 위치를 추적할 수 없습니다.";
            case CANCELED -> "취소된 배송은 위치를 추적할 수 없습니다.";
            // isTerminal() 이 false 인 상태는 위에서 이미 통과했으므로 여기 오지 않는다.
            case WAITING, ASSIGNED, MOVING_TO_PICKUP, PICKED_UP, DELIVERING ->
                    throw new IllegalStateException("구독 가능한 상태를 거부 사유로 설명할 수 없습니다. status=" + status);
        };
    }
}
