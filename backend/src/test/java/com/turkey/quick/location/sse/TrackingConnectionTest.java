package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 스프링을 띄우지 않는다. HTTP 응답에 붙지 않은 {@code SseEmitter} 는 전송 내용을 내부에 모아
 * 두므로(초기화 전 전송은 버퍼링된다) 예외 없이 성공 경로를 확인할 수 있고, {@code complete()} 를
 * 먼저 부르면 실패 경로를 <b>실제로</b> 만들 수 있다.
 *
 * <p>실패 경로가 이 클래스의 존재 이유다. 클라이언트가 탭을 닫은 직후에 전송하면
 * {@code IOException} 이 아니라 {@code IllegalStateException} 이 나는데, 그것이 리스너 스레드로
 * 올라가면 <b>같은 주문의 다른 정상 연결들이 그 이벤트를 못 받는다.</b>
 */
@DisplayName("TrackingConnection")
class TrackingConnectionTest {

    private static final Long ORDER_ID = 1024L;
    private static final String EMITTER_ID = "emitter-1";
    private static final String JSON = "{\"latitude\":37.4979000,\"longitude\":127.0276000}";

    private final SseEmitter emitter = new SseEmitter(60_000L);
    private final TrackingConnection connection =
            new TrackingConnection(EMITTER_ID, ORDER_ID, emitter);

    @Nested
    @DisplayName("전송")
    class Sending {

        @Test
        @DisplayName("초기 이벤트를 전송한다")
        void sendsInitEvent() {
            assertThat(connection.sendInit(JSON)).isTrue();
        }

        @Test
        @DisplayName("위치 이벤트를 전송한다")
        void sendsLocationEvent() {
            assertThat(connection.sendLocation(JSON)).isTrue();
        }

        @Test
        @DisplayName("heartbeat 를 전송한다")
        void sendsHeartbeat() {
            assertThat(connection.sendHeartbeat()).isTrue();
        }
    }

    @Nested
    @DisplayName("종료된 연결")
    class CompletedConnection {

        @Test
        @DisplayName("완료된 연결에 위치를 보내면 예외가 아니라 false 다")
        void reportsFailureInsteadOfThrowing() {
            // send 는 이미 완료된 emitter 에 IllegalStateException 을 던진다(Assert.state).
            // IOException 만 잡는 구현이라면 이 케이스에서 예외가 올라간다.
            connection.complete();

            assertThat(connection.sendLocation(JSON)).isFalse();
        }

        @Test
        @DisplayName("완료된 연결에 heartbeat 를 보내도 false 다")
        void reportsHeartbeatFailure() {
            // heartbeat 실패는 죽은 클라이언트를 조기에 정리하는 신호로도 쓰인다.
            connection.complete();

            assertThat(connection.sendHeartbeat()).isFalse();
        }

        @Test
        @DisplayName("두 번 완료해도 예외가 아니다")
        void toleratesDoubleComplete() {
            // onTimeout·onError 뒤에 onCompletion 이 이어서 오므로 정리가 두 번 이상 불린다.
            connection.complete();

            connection.complete();
        }
    }

    @Nested
    @DisplayName("식별")
    class Identity {

        @Test
        @DisplayName("주문과 연결 식별자를 노출한다")
        void exposesIdentifiers() {
            // 레지스트리 인덱싱과 로그 파라미터가 이 값들에 의존한다.
            assertThat(connection.orderId()).isEqualTo(ORDER_ID);
            assertThat(connection.emitterId()).isEqualTo(EMITTER_ID);
        }
    }
}
