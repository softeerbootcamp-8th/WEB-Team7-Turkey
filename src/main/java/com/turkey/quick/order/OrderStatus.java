package com.turkey.quick.order;

import java.util.Set;

/**
 * 배송 주문의 상태 흐름.
 *
 * WAITING → MOVING_TO_PICKUP → DELIVERING → COMPLETED
 * 어느 상태에서든 CANCELED 로 전이될 수 있다(완료 상태 제외).
 */
public enum OrderStatus {

    /** 배차 대기: 주문 생성 직후, 라이더를 찾는 중 */
    WAITING,

    /** 픽업지로 이동 중: 라이더가 콜을 수락함 */
    MOVING_TO_PICKUP,

    /** 배송 중: 라이더가 물품을 픽업함 */
    DELIVERING,

    /** 완료: 도착지 전달 및 정산 완료 */
    COMPLETED,

    /** 취소 */
    CANCELED;

    private Set<OrderStatus> nextStates() {
        return switch (this) {
            case WAITING -> Set.of(MOVING_TO_PICKUP, CANCELED);
            case MOVING_TO_PICKUP -> Set.of(DELIVERING, CANCELED);
            case DELIVERING -> Set.of(COMPLETED, CANCELED);
            case COMPLETED, CANCELED -> Set.of();
        };
    }

    /** 현재 상태에서 target 상태로 전이 가능한지 검증한다. */
    public boolean canTransitionTo(OrderStatus target) {
        return nextStates().contains(target);
    }
}
