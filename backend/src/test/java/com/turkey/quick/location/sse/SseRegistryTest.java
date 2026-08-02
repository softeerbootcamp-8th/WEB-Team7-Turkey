package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SseRegistry")
class SseRegistryTest {

    private final SseRegistry registry = new SseRegistry();

    @Test
    @DisplayName("등록하지 않은 배송을 조회하면 빈 컬렉션을 반환한다")
    void returnsEmptyWhenNothingRegistered() {
        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("등록한 연결이 조회된다")
    void returnsRegisteredConnection() {
        SseEmitter emitter = new SseEmitter();

        registry.add(1L, emitter);

        assertThat(registry.connectionOf(1L)).containsExactly(emitter);
    }

    @Test
    @DisplayName("같은 배송에 연결이 여러 개면 전부 조회된다")
    void returnsAllConnectionsForSameOrder() {
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();

        registry.add(1L, first);
        registry.add(1L, second);

        assertThat(registry.connectionOf(1L)).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("연결 하나를 제거해도 같은 배송의 다른 연결은 남는다")
    void removingOneConnectionKeepsTheOthers() {
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();
        registry.add(1L, first);
        registry.add(1L, second);

        registry.remove(1L, first);

        assertThat(registry.connectionOf(1L)).containsExactly(second);
    }

    @Test
    @DisplayName("마지막 연결까지 제거하면 빈 컬렉션을 반환한다")
    void removingLastConnectionLeavesEmpty() {
        SseEmitter emitter = new SseEmitter();
        registry.add(1L, emitter);

        registry.remove(1L, emitter);

        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("다른 배송의 연결에는 영향을 주지 않는다")
    void keepsOtherOrdersIsolated() {
        SseEmitter orderOneEmitter = new SseEmitter();
        SseEmitter orderTwoEmitter = new SseEmitter();
        registry.add(1L, orderOneEmitter);
        registry.add(2L, orderTwoEmitter);

        registry.remove(1L, orderOneEmitter);

        assertThat(registry.connectionOf(1L)).isEmpty();
        assertThat(registry.connectionOf(2L)).containsExactly(orderTwoEmitter);
    }
}
