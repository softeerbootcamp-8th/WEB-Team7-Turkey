package com.turkey.quick.location.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class SseRegistry {
    private final ConcurrentHashMap<Long, Set<SseEmitter>> registry = new ConcurrentHashMap<>();

    public void add(Long orderId, SseEmitter emitter) {
        registry.computeIfAbsent(orderId, id -> new CopyOnWriteArraySet<>()).add(emitter);
    }

    public void remove(Long orderId, SseEmitter emitter) {
        registry.computeIfPresent(orderId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    public Set<SseEmitter> connectionOf(Long orderId) {
        return registry.getOrDefault(orderId, Set.of());
    }

    /**
     * 레지스트리를 통째로 비운다. 인스턴스 로컬 싱글턴이라 {@code DatabaseCleaner}로 정리되지
     * 않는데, 통합·E2E 테스트가 매번 auto-increment id를 재사용하므로(TRUNCATE) 이전 테스트의
     * 연결이 다음 테스트의 같은 배송 id와 섞일 수 있다 — {@code IntegrationTestSupport}가
     * 매 테스트 전에 호출한다.
     */
    public void clear() {
        registry.clear();
    }
}
