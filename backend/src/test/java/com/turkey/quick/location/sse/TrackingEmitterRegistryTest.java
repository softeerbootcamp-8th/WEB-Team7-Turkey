package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 검증의 핵심은 <b>주문별 인덱싱이 새지 않는 것</b>이다. 마지막 연결이 빠질 때 빈 맵을 남기면
 * 추적한 주문 수만큼 누수되고, 순회 중 변경이 섞이면 리스너가 전송 실패를 정리하다 터진다.
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
        @DisplayName("같은 주문에 연결 여러 개를 담는다")
        void holdsMultipleConnectionsPerOrder() {
            registry.add(connection(ORDER_ID, "a"));
            registry.add(connection(ORDER_ID, "b"));

            assertThat(registry.connectionsOf(ORDER_ID)).hasSize(2);
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
        @DisplayName("지목한 연결만 빠진다")
        void removesOnlyTargetedConnection() {
            registry.add(connection(ORDER_ID, "a"));
            registry.add(connection(ORDER_ID, "b"));

            registry.remove(ORDER_ID, "a");

            assertThat(registry.connectionsOf(ORDER_ID))
                    .extracting(TrackingConnection::emitterId)
                    .containsExactly("b");
        }

        @Test
        @DisplayName("같은 연결을 두 번 제거해도 오류가 아니다")
        void isIdempotent() {
            // onTimeout·onError 뒤에 onCompletion 이 이어 오므로 정리가 두 번 이상 불린다.
            registry.add(connection(ORDER_ID, "a"));
            registry.remove(ORDER_ID, "a");

            registry.remove(ORDER_ID, "a");

            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("등록되지 않은 연결을 제거해도 오류가 아니다")
        void toleratesUnknownConnection() {
            registry.remove(ORDER_ID, "never-added");

            assertThat(registry.size()).isZero();
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
