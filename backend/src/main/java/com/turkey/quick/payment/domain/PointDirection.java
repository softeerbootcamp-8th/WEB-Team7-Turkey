package com.turkey.quick.payment.domain;

/**
 * 포인트 증감 방향.
 * 거래 유형마다 방향이 하나로 고정되므로 직접 지정하지 않고
 * {@link PointTransactionType#direction()} 에서 파생한다(DB ck_point_transaction_type_direction).
 */
public enum PointDirection {
    /** 적립: balance_after = balance_before + amount */
    CREDIT,
    /** 차감: balance_after = balance_before - amount */
    DEBIT
}
