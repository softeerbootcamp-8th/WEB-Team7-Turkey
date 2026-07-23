package com.turkey.quick.member.domain;

/** 계정 상태. WITHDRAWN 전이 시 withdrawn_at 을 반드시 함께 기록한다(DB CHECK와 쌍). */
public enum MemberStatus {
    ACTIVE,
    SUSPENDED,
    WITHDRAWN
}
