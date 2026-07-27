package com.turkey.quick.order.domain;

/** 요금 정책 적용 상태. 동시에 ACTIVE 일 수 있는 정책은 최대 1개다. */
public enum FarePolicyStatus {
    ACTIVE,
    INACTIVE
}
