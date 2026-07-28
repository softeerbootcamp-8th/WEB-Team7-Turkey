package com.turkey.quick.common.auth;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 전용 인메모리 대체 구현. 로컬 Redis 없이 서비스·통합·E2E 테스트를 돌리기 위한 것으로,
 * InMemoryVerificationCodeStore와 같은 이유다. TTL은 실제로 만료시키지 않고 값 존재만 흉내낸다.
 */
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, Map<String, String>> sessions = new ConcurrentHashMap<>();

    @Override
    public void create(String sessionId, Long memberId, String role, Duration ttl) {
        sessions.put(sessionId, Map.of("memberId", String.valueOf(memberId), "role", role));
    }

    @Override
    public Optional<Long> findMemberId(String sessionId) {
        Map<String, String> session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(session.get("memberId")));
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    public Map<String, String> get(String sessionId) {
        return sessions.get(sessionId);
    }
}
