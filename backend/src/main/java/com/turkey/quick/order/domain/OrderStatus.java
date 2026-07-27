package com.turkey.quick.order.domain;

import java.util.Set;

/**
 * 배송 주문의 상태 흐름.
 *
 * WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED
 *
 * 취소는 배차 전(WAITING)에만 허용한다 — 배차 이후 취소는 MVP 범위 밖이며(라이더 배차 포기도 마찬가지),
 * DDL 의 ck_delivery_assignment 도 같은 규칙을 강제한다: status='CANCELED' 인 행은
 * assigned_rider_id/assigned_at 이 NULL 이어야 하므로 라이더가 배정된 주문은 CANCELED 로 갈 수 없다.
 *
 * 상태 변경은 요청 값으로 덮어쓰지 않고 "현재 상태 + 수행 행위"로 검증한다.
 * 이 enum 은 전이 가능 여부만 판정하고, 실제 전이와 시각 컬럼 기록은 DeliveryOrder 가 수행한다.
 */
public enum OrderStatus {

    /** 배차 대기: 주문 생성 직후, 라이더를 찾는 중 */
    WAITING,

    /** 배차 완료: 라이더가 콜을 수락해 배정됨(라이더는 이 시점에 BUSY 가 된다) */
    ASSIGNED,

    /** 픽업지로 이동 중 */
    MOVING_TO_PICKUP,

    /** 픽업 완료: 라이더가 물품을 수령함 */
    PICKED_UP,

    /** 배송 중: 도착지로 이동 중 */
    DELIVERING,

    /** 완료: 도착지 전달 완료(정산 내역이 생성된다) */
    COMPLETED,

    /** 취소: 배차 전 고객 취소만 존재한다 */
    CANCELED;

    private Set<OrderStatus> nextStates() {
        return switch (this) {
            case WAITING -> Set.of(ASSIGNED, CANCELED);
            case ASSIGNED -> Set.of(MOVING_TO_PICKUP);
            case MOVING_TO_PICKUP -> Set.of(PICKED_UP);
            case PICKED_UP -> Set.of(DELIVERING);
            case DELIVERING -> Set.of(COMPLETED);
            case COMPLETED, CANCELED -> Set.of();
        };
    }

    /** 현재 상태에서 target 상태로 전이 가능한지 검증한다. */
    public boolean canTransitionTo(OrderStatus target) {
        return nextStates().contains(target);
    }
}
