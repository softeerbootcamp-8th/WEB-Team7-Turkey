package com.turkey.quick.location.sse;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>단위 테스트</b> 전용 인메모리 대체({@code InMemoryRiderLocationStore} 와 같은 이유).
 * 스프링 빈으로 등록하지 않고 테스트가 직접 {@code new} 로 만든다 — 통합·E2E 는 실제 Redis 를 쓴다.
 *
 * <p>흉내내는 것은 <b>의미론뿐</b>이다. 여러 인스턴스의 요청이 Redis 서버 한 곳에서 직렬화되는
 * 실제 원자성은 이 구현으로 검증되지 않는다(단위 테스트의 스레드는 한 JVM 안에만 있다).
 * 그 검증은 {@code RedisTrackingConnectionLimiterIntegrationTest} 가 실제 Redis 로 한다.
 */
public class InMemoryTrackingConnectionLimiter implements TrackingConnectionLimiter {

    /** orderId → (emitterId → 마지막 생존 확인 시각). ZSET 을 그대로 옮긴 모양이다. */
    private final Map<Long, Map<String, Instant>> connections = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(Long orderId, String emitterId, Instant now) {
        // compute 는 ConcurrentHashMap 이 해당 키에 대해 원자적으로 실행하므로 "정리 → 세기 →
        // 등록"이 쪼개지지 않는다. Lua 스크립트와 같은 의미론이다.
        boolean[] acquired = {false};
        connections.compute(orderId, (id, alive) -> {
            Map<String, Instant> next = alive == null ? new ConcurrentHashMap<>() : alive;
            next.entrySet().removeIf(entry ->
                    entry.getValue().isBefore(TrackingStreamPolicy.staleCutoff(now)));
            if (!next.containsKey(emitterId)
                    && next.size() >= TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER) {
                acquired[0] = false;
                return next;
            }
            next.put(emitterId, now);
            acquired[0] = true;
            return next;
        });
        return acquired[0];
    }

    @Override
    public void release(Long orderId, String emitterId) {
        connections.computeIfPresent(orderId, (id, alive) -> {
            alive.remove(emitterId);
            // 빈 맵을 남기지 않는 것은 Redis 가 빈 ZSET 키를 스스로 지우는 것과 같은 의미론이다.
            return alive.isEmpty() ? null : alive;
        });
    }

    @Override
    public void touch(Long orderId, String emitterId, Instant now) {
        connections.computeIfAbsent(orderId, id -> new ConcurrentHashMap<>()).put(emitterId, now);
    }

    /** 테스트가 집계 상태를 직접 들여다볼 때 쓴다. TTL 은 흉내내지 않으므로 stale 정리 전 값이다. */
    public int aliveCount(Long orderId, Instant now) {
        Map<String, Instant> alive = connections.get(orderId);
        if (alive == null) {
            return 0;
        }
        return (int) alive.values().stream()
                .filter(at -> !at.isBefore(TrackingStreamPolicy.staleCutoff(now)))
                .count();
    }
}
