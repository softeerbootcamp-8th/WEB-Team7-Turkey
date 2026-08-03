package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SseRelay")
class SseRelayTest {

    /**
     * 팬아웃 도입(#317) 이후 relay 는 {@code LocationPayload} 가 아니라 <b>발행 지점에서 직렬화된
     * JSON 문자열</b>을 받는다. 구독자가 파싱하지 않고 그대로 흘리는 설계라 그렇다.
     */
    private static final String LOCATION_JSON =
            "{\"latitude\":37.4979,\"longitude\":127.0276,\"measuredAt\":\"2026-08-03T00:00:00Z\","
            + "\"accuracyMeters\":null}";

    private final SseRegistry registry = new SseRegistry();
    private final SseRelay relay = new SseRelay(registry);

    @Test
    @DisplayName("구독 중인 연결에 위치를 전송한다")
    void sendsLocationToSubscribedConnection() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add(1L, emitter);

        relay.publish(1L, LOCATION_JSON);

        verify(emitter).send(LOCATION_JSON);
    }

    @Test
    @DisplayName("같은 배송의 모든 연결에 전송한다")
    void sendsToAllConnectionsOfSameDelivery() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add(1L, first);
        registry.add(1L, second);

        relay.publish(1L, LOCATION_JSON);

        verify(first).send(LOCATION_JSON);
        verify(second).send(LOCATION_JSON);
    }

    @Test
    @DisplayName("구독 중인 연결이 없으면 아무 일도 하지 않는다")
    void doesNothingWhenNoSubscribers() {
        relay.publish(999L, LOCATION_JSON);
        // 예외 없이 조용히 끝나는지만 확인한다 — 모든 인스턴스가 모든 이벤트를 받아 대부분
        // 여기로 오는 것이 팬아웃의 정상 경로다.
    }

    @Test
    @DisplayName("전송에 실패한 연결은 레지스트리에서 제거된다")
    void removesFailedConnectionFromRegistry() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("broken")).when(broken).send(anyString());
        registry.add(1L, broken);

        relay.publish(1L, LOCATION_JSON);

        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("한 연결이 실패해도 같은 배송의 다른 연결은 정상 전송된다")
    void isolatesFailureFromOtherConnections() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(broken).send(anyString());
        registry.add(1L, broken);
        registry.add(1L, healthy);

        relay.publish(1L, LOCATION_JSON);

        verify(healthy).send(LOCATION_JSON);
        assertThat(registry.connectionOf(1L)).containsExactly(healthy);
    }
}
