package com.turkey.quick.payment.domain;

/** 포인트 충전 결제 수단. MVP 는 모킹 흐름이지만 값은 실제 수단을 반영해 둔다. */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER
}
