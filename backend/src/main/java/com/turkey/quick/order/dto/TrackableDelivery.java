package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;

/**
 * 실시간 위치 구독(#77)에 필요한 배송 정보만 담은 투영.
 *
 * <p><b>엔터티를 대신 쓰지 않는 이유가 이 record 의 존재 이유다.</b> OSIV 가 꺼져 있고
 * ({@code open-in-view: false}) {@code DeliveryOrder.customer}·{@code assignedRider} 가 모두
 * {@code LAZY} 라, 엔터티를 트랜잭션 밖으로 올려 연관을 만지면
 * {@code LazyInitializationException} 이 난다. SSE 는 그 위험이 특히 큰 경로다 — 값을
 * 레지스트리에 담아 두고 <b>emitter 콜백(요청 스레드도 아니고 트랜잭션도 없다)</b>에서 다시
 * 읽기 때문이다. 게다가 그 예외는 {@code GlobalExceptionHandler} 가 잡지 않아
 * {@code ApiResponse} 형태가 아닌 500 으로 나간다.
 *
 * <p>그래서 조회 단계에서 필요한 세 값만 뽑아 나온다. 여기 필드를 늘리려는 생각이 들면
 * <b>정말 스트림 수명 동안 들고 있어야 하는 값인지</b> 먼저 따진다 — 화면 초기 렌더에 필요한
 * 타임라인·라이더 연락처·요금은 {@code DeliveryDetailResponse}(REST 상세, #46)의 몫이다.
 *
 * @param orderId 배송요청 식별자
 * @param riderId 배정된 라이더의 {@code member_id}. Redis 최신 위치 저장 키와 같은 축이다.
 *                배차 전({@code WAITING})에는 null 이므로, 이 값을 쓰기 전에
 *                {@link OrderStatus#isTrackable()} 를 먼저 통과시켜야 한다
 * @param status  조회 시점의 배송 상태
 */
public record TrackableDelivery(
        Long orderId,
        Long riderId,
        OrderStatus status
) {
}
