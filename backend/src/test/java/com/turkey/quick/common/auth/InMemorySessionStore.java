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
    private final ConcurrentHashMap<String, Integer> extendCounts = new ConcurrentHashMap<>();

    @Override
    public void create(String sessionId, Long memberId, Duration ttl) {
        sessions.put(sessionId, Map.of("memberId", String.valueOf(memberId)));
    }

    @Override
    public Optional<Long> findMemberId(String sessionId) {
        Map<String, String> session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(session.get("memberId")));
    }

    /**
     * 실제 만료를 흉내내지 않으므로 TTL을 다시 걸 것이 없다. 대신 <b>호출 횟수만 센다</b> —
     * 인터셉터가 슬라이딩을 실제로 했는지(#439) 검증할 수단이 필요하다.
     */
    @Override
    public void extend(String sessionId, Duration ttl) {
        if (sessions.containsKey(sessionId)) {
            extendCounts.merge(sessionId, 1, Integer::sum);
        }
    }

    public int extendCount(String sessionId) {
        return extendCounts.getOrDefault(sessionId, 0);
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    public Map<String, String> get(String sessionId) {
        return sessions.get(sessionId);
    }
}
