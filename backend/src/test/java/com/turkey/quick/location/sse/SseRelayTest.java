package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SseRelay relay = new SseRelay(registry, meterRegistry);

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

    @Test
    @DisplayName("closeAll 은 그 배송의 모든 연결을 닫고 레지스트리를 비운다 (#450)")
    void closeAllCompletesAndClearsRegistry() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add(1L, first);
        registry.add(1L, second);

        relay.closeAll(1L);

        verify(first).complete();
        verify(second).complete();
        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("closeAll 은 다른 배송의 연결을 건드리지 않는다 (#450)")
    void closeAllLeavesOtherDeliveriesAlone() {
        SseEmitter target = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        registry.add(1L, target);
        registry.add(2L, other);

        relay.closeAll(1L);

        verify(target).complete();
        verify(other, never()).complete();
        assertThat(registry.connectionOf(2L)).containsExactly(other);
    }

    @Test
    @DisplayName("이미 완료된 emitter 여도 예외를 내지 않고 레지스트리에서 뺀다 (#450)")
    void closeAllSurvivesAlreadyCompletedEmitter() {
        // complete() 는 이미 완료·타임아웃된 emitter 에 IllegalStateException 을 던진다.
        // 그 하나 때문에 나머지 정리가 멈추면 안 되고, 정리 자체는 반드시 일어나야 한다.
        SseEmitter completed = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(completed).complete();
        registry.add(1L, completed);

        assertThatCode(() -> relay.closeAll(1L)).doesNotThrowAnyException();

        assertThat(registry.connectionOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("연결이 없어도 조용히 끝난다 — 다른 인스턴스가 그 고객을 들고 있는 경우 (#450)")
    void closeAllIsNoopWhenNoConnection() {
        // 팬아웃이라 모든 인스턴스가 종료 신호를 받지만, 그 배송의 연결을 가진 인스턴스는 보통
        // 하나뿐이다. 나머지는 여기로 들어와 아무 일도 하지 않아야 한다.
        assertThatCode(() -> relay.closeAll(999L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 연결의 이전 전송이 아직 안 끝났으면 새 메시지는 permit 을 오래 붙잡지 않고 버린다")
    void coalescesMessagesForSameInFlightEmitter() throws InterruptedException, IOException {
        // #566 재검증에서 드러난 문제(전역 세마포어가 연결을 구분하지 않아, 정체된 연결 하나가
        // permit 을 계속 새로 소모해 무관한 배송까지 굶김)의 회귀 테스트. send() 를 붙잡아
        // "아직 전송 중"인 상태를 흉내 낸 뒤, 같은 연결로 온 두 번째 메시지가 send() 를 또
        // 부르지 않고 즉시 버려지는지 확인한다.
        SseEmitter slow = mock(SseEmitter.class);
        registry.add(9L, slow);
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstSendStarted.countDown();
            releaseFirstSend.await();
            return null;
        }).when(slow).send(anyString());

        Thread first = new Thread(() -> relay.publish(9L, LOCATION_JSON));
        first.start();
        assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();

        relay.publish(9L, LOCATION_JSON);

        verify(slow, times(1)).send(LOCATION_JSON);
        assertThat(meterRegistry.get("sse.fanout.coalesced").counter().count()).isEqualTo(1.0);

        releaseFirstSend.countDown();
        first.join(1000);
    }

    @Test
    @DisplayName("같은 배송의 다른 연결은 한쪽이 정체돼도 굶지 않는다 — 멀티탭 격리")
    void doesNotStarveOtherEmitterOfSameDeliveryWhileOneIsInFlight() throws InterruptedException, IOException {
        // deliveryId 단위로 막으면 이 테스트가 실패한다 — 같은 배송을 보는 다른 탭(healthy)이
        // 느린 탭(slow) 하나 때문에 같이 굶는 것이 바로 되돌린 첫 구현의 버그였다.
        SseEmitter slow = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        registry.add(1L, slow);
        registry.add(1L, healthy);
        CountDownLatch slowSendStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            slowSendStarted.countDown();
            releaseSlowSend.await();
            return null;
        }).when(slow).send(anyString());

        Thread first = new Thread(() -> relay.publish(1L, LOCATION_JSON));
        first.start();
        assertThat(slowSendStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // slow 가 막혀 있는 채로 같은 배송에 두 번째 메시지가 온다. deliveryId 단위로 막았던
        // 이전 구현이었다면 이 publish() 가 즉시 버려져 healthy 도 같이 못 받았을 것이다.
        relay.publish(1L, LOCATION_JSON);
        verify(healthy, times(1)).send(LOCATION_JSON);

        releaseSlowSend.countDown();
        first.join(1000);
    }
}
