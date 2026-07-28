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

    private String key(String sessionId) {
        return KEY_FORMAT.formatted(sessionId);
    }
}
