package com.turkey.quick.location.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

/**
 * 이 인스턴스가 들고 있는 SSE 연결로만 위치를 내보낸다.
 *
 * <p><b>호출자는 {@link TrackingSubscriber} 하나뿐이다</b>(#317). 라이더 위치 POST 경로가 여기를
 * 직접 부르면 다른 인스턴스에 연결된 고객이 이벤트를 못 받으므로, 그 경로는
 * {@link TrackingPublisher} 를 거쳐야 한다.
 *
 * <p>페이로드는 이미 직렬화된 JSON 문자열이다 — 발행 지점에서 만든 것을 그대로 흘린다.
 * 이벤트 이름을 붙이지 않으므로 브라우저 쪽에서는 기본 {@code message} 이벤트로 도착한다
 * (프론트 {@code useTrackingStream.onmessage} 계약).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseRelay {
    private final SseRegistry registry;

    public void publish(Long deliveryId, String locationJson) {
        for (SseEmitter emitter : registry.connectionOf(deliveryId)) {
            try {
                emitter.send(locationJson);
            } catch (IOException | IllegalStateException e) {
                // IllegalStateException은 고객이 탭을 이미 닫아 emitter가 완료된
                // 정상적인 경우다(docs/logging-guidelines.md 「예상 가능한 실패」 → WARN).
                log.warn("event=SSE_SEND_FAILED orderId={} reason={}",
                        deliveryId, "CLIENT_DISCONNECTED");
                registry.remove(deliveryId, emitter);
            }
        }
    }

    /**
     * 이 인스턴스가 들고 있는 그 배송의 연결을 모두 닫는다(#450). 배송이 완료·취소돼 더 보낼 것이
     * 없을 때 {@link TrackingCloseSubscriber} 가 부른다.
     *
     * <p>닫으면 브라우저는 <b>끊긴 것으로 보고 자동 재연결</b>한다(SSE 표준). 그 재연결을 서버가
     * DB 기준으로 409 거부하고, 브라우저는 그제서야 영구 종료로 판정해 화면이 재조회를 건다.
     * 즉 여기서 닫는 것 자체가 신호가 아니라 <b>재질의를 유발하는 계기</b>다.
     *
     * <p><b>{@code complete()} 뒤에 명시적으로 제거한다.</b> 완료 콜백({@code onCompletion})이
     * 레지스트리에서 빼 주긴 하지만 동기로 실행된다는 보장이 없어, 그 사이 도착한 이벤트가 이미
     * 닫힌 emitter 로 전송을 시도하게 된다. 이중 제거는 멱등이라(객체 동일성 비교) 안전하다.
     */
    public void closeAll(Long deliveryId) {
        for (SseEmitter emitter : registry.connectionOf(deliveryId)) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                // 이미 완료·타임아웃된 emitter 다. 정리만 하면 되므로 실패로 취급하지 않는다.
                log.warn("event=SSE_CLOSE_FAILED orderId={} reason={}",
                        deliveryId, e.getClass().getSimpleName());
            } finally {
                registry.remove(deliveryId, emitter);
            }
        }
    }
}
