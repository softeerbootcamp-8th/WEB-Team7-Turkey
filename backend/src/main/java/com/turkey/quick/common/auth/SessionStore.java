package com.turkey.quick.common.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * 로그인 세션의 저장소. 운영에서는 Redis(TTL)를 쓰고, 통합·E2E 테스트에서는 로컬 Redis 없이
 * 돌 수 있도록 인메모리 구현으로 대체한다(VerificationCodeStore와 같은 이유).
 */
public interface SessionStore {

    /**
     * 세션 TTL. 로그인 시점의 최초 TTL이자 슬라이딩 갱신 단위다(#439). 세션 쿠키 Max-Age는 더
     * 이상 이 값과 같지 않다 — 실제 만료 판정은 이 TTL(Redis) 하나만 하고, 쿠키는 그와 무관하게
     * 길게 한 번만 발급한다({@link SessionCookie}).
     */
    Duration DEFAULT_TTL = Duration.ofHours(2);

    /**
     * 세션을 생성한다. 값 형식은 docs/03-erd.md 5절에 정의된 {memberId}. role은 저장하지 않는다
     * — 역할·활성 상태 확인은 항상 회원 조회 이후 최신 DB 상태로 하지 이 저장소를 안 쓴다(아래
     * {@link #findMemberId} 참고). 저장했던 적이 있었으나 읽는 코드가 0곳이라 없앴다.
     */
    void create(String sessionId, Long memberId, Duration ttl);

    /**
     * 세션이 존재하면(=Redis TTL로 아직 만료되지 않았으면) 저장된 회원 ID를 반환한다.
     * <b>이 조회 자체는 TTL을 갱신하지 않는다.</b> 슬라이딩은 인증을 통과한 뒤 인터셉터가
     * {@link #extend(String, Duration)}로 명시적으로 한다(#439) — 조회의 부수효과로 두면 인증과
     * 무관한 호출자(로그아웃 시 회원 조회 등)까지 조용히 세션을 연장한다.
     * 역할(role)·활성 상태 확인은 이 저장소가 아니라 회원 조회 이후 최신 DB 상태로 한다 —
     * 세션 생성 이후 계정이 탈퇴됐을 수 있어 Redis에 캐시된 값만으로는 알 수 없기 때문이다.
     */
    Optional<Long> findMemberId(String sessionId);

    /**
     * 존재하는 세션의 TTL을 다시 건다(슬라이딩 갱신, #439). 이미 만료돼 없는 세션은 되살리지
     * 않는다(멱등 — 조용히 무시한다).
     */
    void extend(String sessionId, Duration ttl);

    /** 세션을 삭제한다(로그아웃, #28). 존재하지 않는 세션 ID를 넘겨도 조용히 무시한다(멱등). */
    void delete(String sessionId);
}
