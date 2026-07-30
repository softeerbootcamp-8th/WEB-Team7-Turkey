package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 대체가 {@link TrackingConnectionLimiter} 계약을 지키는지 확인한다. 이 대체가 #77
 * 구독 서비스의 단위 테스트를 지탱하므로, 여기서 계약이 틀어지면 그 테스트가 실제 Redis 와
 * 다르게 동작하는 전제 위에서 통과한다.
 *
 * <p>같은 케이스를 {@code RedisTrackingConnectionLimiterIntegrationTest} 가 실제 Redis 로 다시
 * 검증한다 — 두 구현이 서로 다른 기술로 같은 계약을 지켜야 하는 것이 요점이다.
 */
@DisplayName("InMemoryTrackingConnectionLimiter")
class InMemoryTrackingConnectionLimiterTest {

    private static final Long ORDER_ID = 42L;

    private final InMemoryTrackingConnectionLimiter limiter = new InMemoryTrackingConnectionLimiter();
    private final Instant now = Instant.parse("2026-07-30T02:00:00Z");

    @Nested
    @DisplayName("한도")
    class Limit {

        @Test
        @DisplayName("한도까지는 등록된다")
        void acceptsUpToLimit() {
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                assertThat(limiter.tryAcquire(ORDER_ID, "emitter-" + i, now))
                        .as("%d 번째 연결", i)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("한도를 넘으면 거절한다")
        void rejectsBeyondLimit() {
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                limiter.tryAcquire(ORDER_ID, "emitter-" + i, now);
            }

            assertThat(limiter.tryAcquire(ORDER_ID, "one-too-many", now)).isFalse();
        }

        @Test
        @DisplayName("거절된 연결은 등록되지 않는다")
        void doesNotRegisterRejectedConnection() {
            // 부분 성공이 없어야 한다. 거절하면서 등록하면 상한이 한 칸씩 밀려 내려간다.
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                limiter.tryAcquire(ORDER_ID, "emitter-" + i, now);
            }

            limiter.tryAcquire(ORDER_ID, "one-too-many", now);

            assertThat(limiter.aliveCount(ORDER_ID, now))
                    .isEqualTo(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER);
        }

        @Test
        @DisplayName("배송별로 따로 센다")
        void countsPerOrder() {
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                limiter.tryAcquire(ORDER_ID, "emitter-" + i, now);
            }

            assertThat(limiter.tryAcquire(99L, "other-order", now)).isTrue();
        }

        @Test
        @DisplayName("같은 연결 식별자를 다시 등록해도 한도를 이중으로 쓰지 않는다")
        void doesNotDoubleCountSameEmitter() {
            limiter.tryAcquire(ORDER_ID, "same", now);
            limiter.tryAcquire(ORDER_ID, "same", now);

            assertThat(limiter.aliveCount(ORDER_ID, now)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("해제하면 자리가 다시 생긴다")
        void freesSlotOnRelease() {
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                limiter.tryAcquire(ORDER_ID, "emitter-" + i, now);
            }

            limiter.release(ORDER_ID, "emitter-0");

            assertThat(limiter.tryAcquire(ORDER_ID, "replacement", now)).isTrue();
        }

        @Test
        @DisplayName("두 번 해제해도 자리가 두 칸 생기지 않는다")
        void isIdempotent() {
            // SseEmitter 는 onTimeout·onError 뒤에 onCompletion 이 이어서 오므로 정리가 실제로
            // 두 번 이상 불린다. 정수 카운터라면 여기서 음수로 새어 상한이 무력해진다.
            limiter.tryAcquire(ORDER_ID, "solo", now);

            limiter.release(ORDER_ID, "solo");
            limiter.release(ORDER_ID, "solo");

            assertThat(limiter.aliveCount(ORDER_ID, now)).isZero();
        }

        @Test
        @DisplayName("등록되지 않은 연결을 해제해도 오류가 아니다")
        void toleratesUnknownConnection() {
            limiter.release(ORDER_ID, "never-registered");

            assertThat(limiter.aliveCount(ORDER_ID, now)).isZero();
        }
    }

    @Nested
    @DisplayName("자기치유")
    class SelfHealing {

        @Test
        @DisplayName("갱신되지 않은 연결은 stale 임계를 넘으면 자리를 내놓는다")
        void reclaimsStaleConnections() {
            // kill -9 로 죽은 인스턴스가 남긴 기록이다. 이게 걷어내지지 않으면 그 배송은
            // 영구히 구독 불가가 되고, 이 저장소의 배포 방식에서는 배포마다 일어난다.
            for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                limiter.tryAcquire(ORDER_ID, "dead-" + i, now);
            }
            Instant afterStale = now.plus(TrackingStreamPolicy.STALE_AFTER).plusSeconds(1);

            assertThat(limiter.tryAcquire(ORDER_ID, "fresh", afterStale)).isTrue();
            assertThat(limiter.aliveCount(ORDER_ID, afterStale)).isEqualTo(1);
        }

        @Test
        @DisplayName("갱신된 연결은 자리를 유지한다")
        void keepsTouchedConnections() {
            limiter.tryAcquire(ORDER_ID, "alive", now);
            Instant later = now.plus(TrackingStreamPolicy.STALE_AFTER).plusSeconds(1);

            // heartbeat 가 도는 동안은 살아 있어야 한다. 이 갱신이 없으면 조용한 스트림이
            // 전부 stale 로 판정돼 상한이 사실상 무력해진다.
            limiter.touch(ORDER_ID, "alive", later);

            assertThat(limiter.aliveCount(ORDER_ID, later)).isEqualTo(1);
        }

        @Test
        @DisplayName("stale 임계 직전에는 아직 살아 있다")
        void keepsConnectionJustBeforeStaleThreshold() {
            limiter.tryAcquire(ORDER_ID, "alive", now);

            assertThat(limiter.aliveCount(ORDER_ID, now.plus(TrackingStreamPolicy.STALE_AFTER)))
                    .isEqualTo(1);
        }
    }
}
