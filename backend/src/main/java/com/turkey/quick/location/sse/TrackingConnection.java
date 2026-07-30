package com.turkey.quick.location.sse;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 열려 있는 SSE 연결 하나. <b>전송 실패를 격리하는 지점이다.</b>
 *
 * <p>SSE 이벤트 이름을 여기 모아 둔 이유: 이 이름들이 프론트({@code useTrackingStream}, #196)와의
 * 계약이고, 문자열이 여러 곳에 흩어지면 한쪽만 바뀐다. 스트림은 OpenAPI 대상이 아니라
 * (Orval 이 생성하지 않는다) 컴파일러가 계약을 지켜 주지 않으므로 더 그렇다.
 *
 * <p><b>모든 전송 메서드가 예외를 던지지 않고 boolean 을 돌려준다.</b> 세 가지 이유다:
 * <ul>
 *   <li>{@code ResponseBodyEmitter.send} 는 이미 완료된 emitter 에 대해 {@code IOException} 이
 *       아니라 <b>{@code IllegalStateException}</b> 을 던진다({@code Assert.state}). 클라이언트가
 *       탭을 닫은 직후가 정확히 그 상황이라 정상 운행에서 일어난다.</li>
 *   <li>이 메서드는 Redis 리스너 스레드에서 여러 연결을 순회하며 호출된다. 한 연결의 예외가
 *       순회를 끊으면 <b>뒤에 있는 정상 연결들이 그 이벤트를 못 받는다.</b></li>
 *   <li>{@code RedisMessageListenerContainer} 는 리스너에서 올라온 {@code Throwable} 을 삼켜
 *       로그만 남긴다 — 던져도 아무도 처리하지 않는다.</li>
 * </ul>
 */
@Slf4j
public class TrackingConnection {

    /** 연결 직후 1회. 현재 배송 상태와 마지막으로 알려진 라이더 위치를 함께 싣는다(#77 흐름 ⑥). */
    private static final String INIT_EVENT = "init";

    /** 라이더 위치가 실제로 바뀔 때마다(#78). */
    private static final String LOCATION_EVENT = "location";

    /**
     * 브라우저에게 재연결 간격을 알려 주는 SSE {@code retry:} 필드값(밀리초).
     * 한 번 보내면 그 연결이 끊긴 뒤의 재연결에 계속 적용되므로 {@code init} 에만 싣는다.
     */
    private static final long RECONNECT_HINT_MILLIS = 3_000;

    /** heartbeat 는 SSE 주석 프레임이라 {@code EventSource} 가 무시한다 — 프론트에 핸들러가 필요 없다. */
    private static final String HEARTBEAT_COMMENT = "hb";

    private final String emitterId;
    private final Long orderId;
    private final SseEmitter emitter;

    public TrackingConnection(String emitterId, Long orderId, SseEmitter emitter) {
        this.emitterId = emitterId;
        this.orderId = orderId;
        this.emitter = emitter;
    }

    public String emitterId() {
        return emitterId;
    }

    public Long orderId() {
        return orderId;
    }

    /**
     * @param json 이미 직렬화된 JSON 문자열. 객체를 받아 스프링 컨버터에 맡기지 않는 이유는
     *             <b>발행된 페이로드를 파싱 없이 그대로 흘려보내기 위해서</b>다(#78). 타입에 따라
     *             컨버터가 갈리면 같은 메서드가 문자열은 그대로, 객체는 JSON 으로 쓰는 두 동작을
     *             갖게 된다
     */
    public boolean sendInit(String json) {
        return send(emitter.event()
                .name(INIT_EVENT)
                .reconnectTime(RECONNECT_HINT_MILLIS)
                .data(json));
    }

    public boolean sendLocation(String json) {
        return send(emitter.event().name(LOCATION_EVENT).data(json));
    }

    /**
     * 조용한 구간에 연결을 살려 두기 위한 주석 프레임. CloudFront 는 스트리밍 응답에서 <b>패킷 사이
     * 간격</b>에 타임아웃을 적용하는데, 정지한 라이더의 좌표는 {@code DUPLICATE} 로 판정돼 발행조차
     * 되지 않으므로 이것 없이는 스트림이 무한히 조용해진다.
     *
     * @return 전송에 실패했으면 false — 죽은 클라이언트를 조기에 정리하는 신호로도 쓰인다
     */
    public boolean sendHeartbeat() {
        return send(emitter.event().comment(HEARTBEAT_COMMENT));
    }

    /** 서버가 스트림을 정상 종료한다. {@code EventSource} 는 이 경우 <b>재연결한다</b>. */
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // 이미 완료된 emitter 를 다시 완료하는 경우가 있다(onTimeout 뒤 onCompletion).
            log.debug("event=SSE_COMPLETE_IGNORED orderId={} emitterId={} reason={}",
                    orderId, emitterId, e.getClass().getSimpleName());
        }
    }

    private boolean send(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
            // RuntimeException 이 IllegalStateException 을 포함한다(multi-catch 에 둘을 함께 쓸 수
            // 없다). 그래도 위 javadoc 에 그 타입을 명시해 둔 이유는, 좁혀 잡으려는 사람이
            // IOException 만 잡고 끝내기 쉬운 지점이기 때문이다.
        } catch (IOException | RuntimeException e) {
            // MDC 에 기대지 않고 orderId·emitterId 를 파라미터로 넘긴다 — SSE 전송은 요청 경계를
            // 넘어(리스너·스케줄러 스레드) 일어나므로 요청 MDC 가 비어 있다(docs/logging-guidelines.md).
            log.debug("event=SSE_SEND_FAILED orderId={} emitterId={} reason={}",
                    orderId, emitterId, e.getClass().getSimpleName());
            return false;
        }
    }
}
