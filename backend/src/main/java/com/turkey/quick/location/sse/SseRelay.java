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
}
