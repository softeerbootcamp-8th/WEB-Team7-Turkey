package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * "첫 연결 / 마지막 연결" 판정이 이 클래스의 핵심이다 — 그 판정이 Pub/Sub 채널 구독·해제 시점을
 * 결정하므로, 어긋나면 구독이 새거나(해제되지 않음) 이벤트를 받지 못한다(구독되지 않음).
 */
@DisplayName("TrackingEmitterRegistry")
class TrackingEmitterRegistryTest {

    private static final Long ORDER_ID = 1024L;
    private static final Long OTHER_ORDER_ID = 2048L;

    private final TrackingEmitterRegistry registry = new TrackingEmitterRegistry();

    private TrackingConnection connection(Long orderId, String emitterId) {
        return new TrackingConnection(emitterId, orderId, new SseEmitter(60_000L));
    }

    @Nested
    @DisplayName("등록")
    class Adding {

        @Test
        @DisplayName("주문의 첫 연결이면 true 를 돌려준다")
        void reportsFirstConnection() {
            // 호출자가 이 신호로만 채널을 구독한다.
            assertThat(registry.add(connection(ORDER_ID, "a"))).isTrue();
        }

        @Test
        @DisplayName("두 번째 연결은 첫 연결이 아니다")
        void reportsSubsequentConnection() {
            registry.add(connection(ORDER_ID, "a"));

            assertThat(registry.add(connection(ORDER_ID, "b"))).isFalse();
        }

        @Test
        @DisplayName("다른 주문의 첫 연결은 다시 true 다")
        void reportsFirstConnectionPerOrder() {
            registry.add(connection(ORDER_ID, "a"));

            assertThat(registry.add(connection(OTHER_ORDER_ID, "b"))).isTrue();
        }

        @Test
        @DisplayName("등록한 연결을 주문으로 찾을 수 있다")
        void findsConnectionsByOrder() {
            registry.add(connection(ORDER_ID, "a"));
            registry.add(connection(ORDER_ID, "b"));
            registry.add(connection(OTHER_ORDER_ID, "c"));

            assertThat(registry.connectionsOf(ORDER_ID))
                    .extracting(TrackingConnection::emitterId)
                    .containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("연결이 없는 주문은 빈 목록이다")
        void returnsEmptyForUnknownOrder() {
            // null 을 돌려주면 리스너가 순회 직전에 null 검사를 해야 한다.
            assertThat(registry.connectionsOf(ORDER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("제거")
    class Removing {

        @Test
        @DisplayName("마지막 연결이 사라지면 true 를 돌려준다")
        void reportsLastConnectionRemoved() {
            registry.add(connection(ORDER_ID, "a"));

            assertThat(registry.remove(ORDER_ID, "a")).isTrue();
        }

        @Test
        @DisplayName("연결이 남아 있으면 false 다")
        void reportsRemainingConnections() {
            registry.add(connection(ORDER_ID, "a"));
            registry.add(connection(ORDER_ID, "b"));

            assertThat(registry.remove(ORDER_ID, "a")).isFalse();
            assertThat(registry.connectionsOf(ORDER_ID)).hasSize(1);
        }

        @Test
        @DisplayName("같은 연결을 두 번 제거하면 두 번째는 false 다")
        void doesNotReportLastTwice() {
            // onTimeout 뒤에 onCompletion 이 이어 오므로 정리가 두 번 불린다. 두 번 다 true 면
            // 채널 구독 해제가 두 번 일어난다.
            registry.add(connection(ORDER_ID, "a"));
            registry.remove(ORDER_ID, "a");

            assertThat(registry.remove(ORDER_ID, "a")).isFalse();
        }

        @Test
        @DisplayName("등록되지 않은 연결을 제거해도 오류가 아니다")
        void toleratesUnknownConnection() {
            assertThat(registry.remove(ORDER_ID, "never-added")).isFalse();
        }

        @Test
        @DisplayName("마지막 연결이 빠지면 주문 항목 자체가 사라진다")
        void dropsOrderEntryWhenEmpty() {
            // 빈 맵을 남기면 추적한 주문 수만큼 메모리가 누수된다.
            registry.add(connection(ORDER_ID, "a"));

            registry.remove(ORDER_ID, "a");

            assertThat(registry.size()).isZero();
            assertThat(registry.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("전체 순회")
    class Iterating {

        @Test
        @DisplayName("모든 주문의 연결을 한 번에 돌려준다")
        void returnsEveryConnection() {
            // heartbeat 가 이 인스턴스의 전 연결을 순회할 때 쓴다.
            registry.add(connection(ORDER_ID, "a"));
            registry.add(connection(ORDER_ID, "b"));
            registry.add(connection(OTHER_ORDER_ID, "c"));

            assertThat(registry.all())
                    .extracting(TrackingConnection::emitterId)
                    .containsExactlyInAnyOrder("a", "b", "c");
            assertThat(registry.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("반환된 목록을 고쳐도 레지스트리가 바뀌지 않는다")
        void returnsDefensiveCopy() {
            // 리스너가 순회하면서 실패한 연결을 제거하므로 원본을 노출하면 순회 중 변경이 섞인다.
            registry.add(connection(ORDER_ID, "a"));

            assertThat(registry.connectionsOf(ORDER_ID)).isUnmodifiable();
        }
    }
}
