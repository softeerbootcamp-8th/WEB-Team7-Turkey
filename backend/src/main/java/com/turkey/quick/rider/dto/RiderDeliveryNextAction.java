package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.OrderStatus;

/** 진행 배송 화면에서 현재 상태 다음에 수행할 수 있는 단일 행동. */
public enum RiderDeliveryNextAction {
    START_MOVING_TO_PICKUP,
    PICK_UP,
    START_DELIVERING,
    COMPLETE;

    public static RiderDeliveryNextAction from(OrderStatus status) {
        return switch (status) {
            case ASSIGNED -> START_MOVING_TO_PICKUP;
            case MOVING_TO_PICKUP -> PICK_UP;
            case PICKED_UP -> START_DELIVERING;
            case DELIVERING -> COMPLETE;
            default -> throw new IllegalArgumentException(
                    "진행 중 배송 상태가 아닙니다. status=" + status);
        };
    }
}
