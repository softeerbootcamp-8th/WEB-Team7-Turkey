package com.turkey.quick.payment.domain;

/**
 * 포인트 거래 유형.
 *
 * 각 유형은 증감 방향과 소스 종류를 함께 들고 있다 — DB 의 두 CHECK 제약
 * (ck_point_transaction_type_direction, ck_point_transaction_source)이 고정하는 조합을
 * 타입 수준에서 그대로 표현한 것이다. 방향과 소스를 호출자가 따로 넘기지 않으므로
 * "CHARGE 인데 DEBIT", "SETTLEMENT 인데 주문 FK" 같은 조합은 만들어질 수 없다.
 */
public enum PointTransactionType {

    /** 충전 승인 → 잔액 증가 */
    CHARGE(PointDirection.CREDIT, SourceKind.CHARGE),
    /** 충전 환불 → 충전분 회수 */
    CHARGE_REFUND(PointDirection.DEBIT, SourceKind.CHARGE),

    /** 배송요청 결제 → 잔액 차감 */
    ORDER_USE(PointDirection.DEBIT, SourceKind.ORDER),
    /** 주문 취소 환불 → 차감분 반환 */
    ORDER_REFUND(PointDirection.CREDIT, SourceKind.ORDER),

    /** 배송 완료 정산 → 라이더 잔액 증가 */
    SETTLEMENT(PointDirection.CREDIT, SourceKind.SETTLEMENT),

    /** 출금 요청 → 선차감 */
    WITHDRAWAL(PointDirection.DEBIT, SourceKind.WITHDRAWAL),
    /** 출금 실패 → 선차감분 복구 */
    WITHDRAWAL_REFUND(PointDirection.CREDIT, SourceKind.WITHDRAWAL);

    /** 거래의 근거가 되는 소스 테이블 종류. 유형별로 정확히 하나의 FK 만 채워진다. */
    public enum SourceKind {
        CHARGE,
        ORDER,
        SETTLEMENT,
        WITHDRAWAL
    }

    private final PointDirection direction;
    private final SourceKind sourceKind;

    PointTransactionType(PointDirection direction, SourceKind sourceKind) {
        this.direction = direction;
        this.sourceKind = sourceKind;
    }

    public PointDirection direction() {
        return direction;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }
}
