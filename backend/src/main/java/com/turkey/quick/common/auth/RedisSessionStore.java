package com.turkey.quick.common.auth;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionStore implements SessionStore {

    private static final String KEY_FORMAT = "session:%s";

    private final StringRedisTemplate redisTemplate;

    public RedisSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void create(String sessionId, Long memberId, String role, Duration ttl) {
        String key = key(sessionId);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(ttl);

        // HSET으로 필드 세 개를 한 번에 쓰고, EXPIRE로 TTL을 건다(둘을 하나의 원자적 명령으로
        // 묶는 대신 별도 호출 두 번 — 세션 생성 경로라 그 사이 극히 짧은 창은 감내 가능하다고 판단).
        redisTemplate.opsForHash().putAll(key, Map.of(
                "memberId", String.valueOf(memberId),
                "role", role,
                "expiresAt", expiresAt.toString()
        ));
        redisTemplate.expire(key, ttl);
    }

    @Override
    public Optional<Long> findMemberId(String sessionId) {
        Object memberId = redisTemplate.opsForHash().get(key(sessionId), "memberId");
        if (memberId == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf((String) memberId));
    }

    /**
     * 순서가 중요하다. EXPIRE는 없는 키에 아무 일도 하지 않고 false를 돌려주므로 만료된 세션을
     * 되살리지 않지만, HSET을 먼저 하면 <b>없는 키에 expiresAt만 든 반쪽 세션이 새로 생긴다</b>
     * (memberId가 없어 인증은 못 통과하지만 TTL을 물고 남는 쓰레기 키다). 그래서 EXPIRE로 키
     * 존재를 확인한 뒤에만 값을 갱신한다 — 그 사이에는 TTL을 막 늘려 놓은 상태라 키가 사라지지 않는다.
     */
    @Override
    public void extend(String sessionId, Duration ttl) {
        String key = key(sessionId);
        if (!Boolean.TRUE.equals(redisTemplate.expire(key, ttl))) {
            return;
        }
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(ttl);
        redisTemplate.opsForHash().put(key, "expiresAt", expiresAt.toString());
    }

    @Override
    public void delete(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_FORMAT.formatted(sessionId);
    }
}
