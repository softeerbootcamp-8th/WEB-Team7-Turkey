package com.turkey.quick.payment.domain;

/**
 * 포인트 충전 결제 상태.
 * PENDING 에서 시작해 PAID/FAILED/CANCELED 로 갈리고, PAID 는 REFUNDED(전액 환불)로만 전이한다.
 * 각 상태별 필드 조합은 DB ck_point_charge_state_values 와 엔터티가 함께 강제한다.
 */
public enum PointChargeStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELED,
    REFUNDED
}
