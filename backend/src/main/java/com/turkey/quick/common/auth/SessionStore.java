package com.turkey.quick.common.auth;

import java.time.Duration;

/**
 * 로그인 세션의 저장소. 운영에서는 Redis(TTL)를 쓰고, 통합·E2E 테스트에서는 로컬 Redis 없이
 * 돌 수 있도록 인메모리 구현으로 대체한다(VerificationCodeStore와 같은 이유).
 *
 * role을 회원 도메인 타입(MemberRole)이 아니라 String으로 받는 이유: 이 패키지(common/auth)는
 * 고객·라이더 모두에게 재사용될 인증 인프라라 특정 도메인 enum에 의존하지 않는다.
 */
public interface SessionStore {

    /** 세션을 생성한다. 값 형식은 docs/03-erd.md 5절에 정의된 {memberId, role, expiresAt}. */
    void create(String sessionId, Long memberId, String role, Duration ttl);
}
