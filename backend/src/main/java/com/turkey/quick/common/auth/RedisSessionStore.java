package com.turkey.quick.common.auth;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisSessionStore implements SessionStore {

    private static final String KEY_FORMAT = "session:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 값(JSON) 과 TTL 을 {@code SET ... EX} 한 번으로 함께 건다(#511). 이전엔 {@code HSET} +
     * {@code EXPIRE} 두 호출이라 그 사이 프로세스가 죽으면 TTL 없는 세션 키가 영구히 남을 수
     * 있었다 — 로그인당 1회뿐이라 감내해 왔으나, 단일 호출로 그 창 자체를 없앨 수 있어 바꿨다.
     */
    @Override
    public void create(String sessionId, Long memberId, Duration ttl) {
        String value = objectMapper.writeValueAsString(new SessionValue(memberId));
        redisTemplate.opsForValue().set(key(sessionId), value, ttl);
    }

    /**
     * 배포 직후 짧은 창에는 이전 버전(#511 이전)이 {@code HSET} 으로 써 둔 Hash 타입 키가 아직
     * 살아 있을 수 있다(TTL 최대 2시간). 그 키에 {@code GET}(String 명령)을 쓰면 Redis 가
     * WRONGTYPE 을 던지는데, 이걸 인증 실패로 처리한다 — 로그아웃과 같은 취급이라 재로그인으로
     * 자연히 새 형식 세션이 생긴다. 그 사이 사용자가 겪는 건 "세션이 끊겼다"는 정상적인 401 이지,
     * 원인 불명의 500 이 아니다.
     */
    @Override
    public Optional<Long> findMemberId(String sessionId) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key(sessionId));
        } catch (RedisSystemException e) {
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(value, SessionValue.class).memberId());
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

    /** 세션 값의 JSON 형태(#511). 필드가 늘어도 SET ... EX 한 번으로 쓰는 구조 자체는 안 바뀐다. */
    private record SessionValue(Long memberId) {}
}
