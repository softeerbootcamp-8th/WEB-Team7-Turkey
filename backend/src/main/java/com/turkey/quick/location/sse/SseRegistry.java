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
}
