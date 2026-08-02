package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.turkey.quick.location.dto.LocationPayload;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SseRelay")
class SseRelayTest {

    private static final LocationPayload LOCATION = new LocationPayload(
            new BigDecimal("37.4979"), new BigDecimal("127.0276"), Instant.now(), null);

    private final SseRegistry registry = new SseRegistry();
    private final SseRelay relay = new SseRelay(registry);

    @Test
    @DisplayName("구독 중인 연결에 위치를 전송한다")
    void sendsLocationToSubscribedConnection() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add(1L, emitter);

        relay.publish(1L, LOCATION);

        verify(emitter).send(LOCATION);
    }

    @Test
    @DisplayName("같은 배송의 모든 연결에 전송한다")
    void sendsToAllConnectionsOfSameDelivery() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add(1L, first);
        registry.add(1L, second);

        relay.publish(1L, LOCATION);

        verify(first).send(LOCATION);
        verify(second).send(LOCATION);
    }

    @Test
    @DisplayName("구독 중인 연결이 없으면 아무 일도 하지 않는다")
    void doesNothingWhenNoSubscribers() {
        relay.publish(999L, LOCATION);
        // 예외 없이 조용히 끝나는지만 확인한다 — 배차 전·완료 후 라이더의 정상 경로다.
    }

    @Test
    @DisplayName("전송에 실패한 연결은 레지스트리에서 제거된다")
    void removesFailedConnectionFromRegistry() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("broken")).when(broken).send(any(LocationPayload.class));
        registry.add(1L, broken);

        relay.publish(1L, LOCATION);

        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("한 연결이 실패해도 같은 배송의 다른 연결은 정상 전송된다")
    void isolatesFailureFromOtherConnections() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(broken).send(any(LocationPayload.class));
        registry.add(1L, broken);
        registry.add(1L, healthy);

        relay.publish(1L, LOCATION);

        verify(healthy).send(LOCATION);
        assertThat(registry.connectionOf(1L)).containsExactly(healthy);
    }
}
