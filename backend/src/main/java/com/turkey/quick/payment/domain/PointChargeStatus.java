package com.turkey.quick.payment.domain;

/**
 * 포인트 충전 결제 상태.
 * PENDING 에서 시작해 PAID/FAILED/CANCELED 로 갈리고, PAID 는 REFUNDED(전액 환불)로만 전이한다.
 * 각 상태별 필드 조합은 DB ck_point_charge_state_values 와 엔터티가 함께 강제한다.
 *
 * <p><b>PENDING 이 두 의미를 겸한다</b>(#33, #34): "결제창만 열린 건"과 "PG 승인은 받았는데 포인트
 * 반영이 끝나지 않은 건"이다. 후자는 {@code provider_payment_key} 유무로만 구분되고, 취소 가능 여부와
 * 대사 대상 여부가 여기서 갈린다. 사실상 {@code APPROVING} 을 enum 값 없이 표현한 것이다.
 * 실 PG 연동 시 값으로 분리할지 미결(워크로그 {@code 2026-07-31-34-point-charge-cancel.md}).
 */
public enum PointChargeStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELED,
    REFUNDED
}
