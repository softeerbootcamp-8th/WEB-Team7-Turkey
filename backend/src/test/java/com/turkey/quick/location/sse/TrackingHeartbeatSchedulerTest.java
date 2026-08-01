package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 스프링을 띄우지 않는다. 검증하려는 것은 스케줄링 배선이 아니라 <b>tick 한 번의 판정</b>이고,
 * 그건 메서드를 직접 불러 확인하는 것이 정확하다(실제 주기 동작은
 * {@code TrackingHeartbeatE2ETest} 가 본다).
 *
 * <p>죽은 연결은 미리 {@code complete()} 해 둔 emitter 로 만든다 — 그 상태의 전송은 실제로
 * 실패하므로 "실패를 흉내낸" 것이 아니다.
 */
@DisplayName("TrackingHeartbeatScheduler")
class TrackingHeartbeatSchedulerTest {

    private static final Long ORDER_ID = 1024L;

    private final TrackingEmitterRegistry registry = new TrackingEmitterRegistry();
    private final InMemoryTrackingConnectionLimiter limiter = new InMemoryTrackingConnectionLimiter();
    private final TrackingSubscriptionService subscriptionService =
            new TrackingSubscriptionService(null, null, limiter, registry, null);
    private final TrackingHeartbeatScheduler scheduler =
            new TrackingHeartbeatScheduler(registry, limiter, subscriptionService);

    private TrackingConnection register(String emitterId, boolean alive) {
        return register(emitterId, alive, Instant.now());
    }

    /** @param registeredAt 연결 집계에 기록할 시각. stale 상황을 대기 없이 만들 때 과거로 준다 */
    private TrackingConnection register(String emitterId, boolean alive, Instant registeredAt) {
        TrackingConnection connection =
                new TrackingConnection(emitterId, ORDER_ID, new SseEmitter(60_000L));
        registry.add(connection);
        limiter.tryAcquire(ORDER_ID, emitterId, registeredAt);
        if (!alive) {
            connection.complete();
        }
        return connection;
    }

    @Test
    @DisplayName("살아 있는 연결에는 heartbeat 를 보내고 그대로 둔다")
    void keepsAliveConnections() {
        register("alive", true);

        scheduler.sendHeartbeats();

        assertThat(registry.connectionsOf(ORDER_ID))
                .extracting(TrackingConnection::emitterId)
                .containsExactly("alive");
    }

    @Test
    @DisplayName("죽은 연결은 레지스트리에서 제거한다")
    void removesDeadConnections() {
        // 전송 실패가 곧 "클라이언트가 사라졌다"는 신호다. SSE 는 쓰기를 시도할 때만 끊김을
        // 알 수 있으므로, 이 정리가 없으면 emitter 타임아웃(5분)까지 자리를 차지한다.
        register("dead", false);

        scheduler.sendHeartbeats();

        assertThat(registry.connectionsOf(ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("죽은 연결은 연결 집계에서도 빠진다")
    void releasesSlotOfDeadConnections() {
        register("dead", false);

        scheduler.sendHeartbeats();

        assertThat(limiter.aliveCount(ORDER_ID, Instant.now())).isZero();
    }

    @Test
    @DisplayName("죽은 연결 하나가 다른 연결의 heartbeat 를 막지 않는다")
    void isolatesFailureBetweenConnections() {
        // 한 연결의 예외가 순회를 끊으면 뒤에 있는 정상 연결들이 keep-alive 를 못 받아
        // 줄줄이 끊긴다. TrackingConnection 이 예외를 boolean 으로 바꾸는 이유가 이것이다.
        register("dead", false);
        register("alive", true);

        scheduler.sendHeartbeats();

        assertThat(registry.connectionsOf(ORDER_ID))
                .extracting(TrackingConnection::emitterId)
                .containsExactly("alive");
    }

    @Test
    @DisplayName("살아 있는 연결의 생존 시각을 갱신한다")
    void refreshesLivenessOfAliveConnections() {
        // 갱신하지 않으면 조용한 스트림이 stale 임계를 넘겨 전부 걷어내지고, 연결 상한이
        // 사실상 무력해진다.
        //
        // 이미 stale 인 시각으로 등록해 두고 heartbeat 뒤에 되살아나는지 본다. 등록 시각과
        // touch 시각이 둘 다 "지금"이면 갱신이 실제로 일어났는지 구분할 수 없어서다.
        Instant longAgo = Instant.now().minus(TrackingStreamPolicy.STALE_AFTER).minusSeconds(1);
        register("alive", true, longAgo);
        assertThat(limiter.aliveCount(ORDER_ID, Instant.now()))
                .as("등록 시각이 이미 stale 이라 갱신 전에는 0 이어야 한다")
                .isZero();

        scheduler.sendHeartbeats();

        assertThat(limiter.aliveCount(ORDER_ID, Instant.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("연결이 없으면 아무 일도 하지 않는다")
    void doesNothingWithoutConnections() {
        scheduler.sendHeartbeats();

        assertThat(registry.size()).isZero();
    }
}
