package com.turkey.quick.member.domain;

/** 휴대전화 인증번호 발급 목적. 목적에 따라 회원 가입 여부 확인 방향이 달라진다. */
public enum VerificationPurpose {
    SIGNUP,
    FIND_ID
}
