package com.turkey.quick.member.domain;

/**
 * 약관 적용 대상.
 * COMMON 은 고객·라이더 공통 약관, CUSTOMER/RIDER 는 역할 전용 약관이다.
 * 회원 역할(MemberRole)과 값이 겹치지 않도록 COMMON 을 별도로 둔다.
 */
public enum TermTargetRole {
    COMMON,
    CUSTOMER,
    RIDER
}
