package com.turkey.quick.common.auth;

import java.time.Duration;
import java.util.Optional;

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

    /**
     * 세션이 존재하면(=Redis TTL로 아직 만료되지 않았으면) 저장된 회원 ID를 반환한다.
     * 이 조회는 TTL을 갱신(슬라이딩)하지 않는다 — 로그인 시점에 정한 고정 TTL을 그대로 유지한다(#27 계약 확정).
     * 역할(role)·활성 상태 확인은 이 저장소가 아니라 회원 조회 이후 최신 DB 상태로 한다 —
     * 세션 생성 이후 계정이 탈퇴됐을 수 있어 Redis에 캐시된 값만으로는 알 수 없기 때문이다.
     */
    Optional<Long> findMemberId(String sessionId);

    /** 세션을 삭제한다(로그아웃, #28). 존재하지 않는 세션 ID를 넘겨도 조용히 무시한다(멱등). */
    void delete(String sessionId);
}
