package com.turkey.quick.common.auth;

import java.time.Duration;
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

        // HSET으로 필드 두 개를 한 번에 쓰고, EXPIRE로 TTL을 건다(둘을 하나의 원자적 명령으로
        // 묶는 대신 별도 호출 두 번 — 세션 생성 경로라 그 사이 극히 짧은 창은 감내 가능하다고 판단).
        redisTemplate.opsForHash().putAll(key, Map.of(
                "memberId", String.valueOf(memberId),
                "role", role
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
     * EXPIRE는 없는 키를 새로 만들지 않으므로(있는 키의 TTL만 바꾼다) 그 자체로 원자적이고,
     * 이미 만료돼 없는 세션을 되살릴 여지가 없다. 예전엔 expiresAt 필드도 함께 갱신했으나
     * 어디서도 읽지 않는 값이라 필드째로 없앴다 — HSET이 사라지면서 "EXPIRE 성공 뒤 로그아웃의
     * DEL이 끼어들어 HSET이 반쪽 세션을 되살리는" race도 같이 사라진다.
     */
    @Override
    public void extend(String sessionId, Duration ttl) {
        redisTemplate.expire(key(sessionId), ttl);
    }

    @Override
    public void delete(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_FORMAT.formatted(sessionId);
    }
}
