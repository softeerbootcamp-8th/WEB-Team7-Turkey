package com.turkey.quick.location.sse;

import com.turkey.quick.location.dto.LocationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseRelay {
    private final SseRegistry registry;

    public void publish(Long deliveryId, LocationPayload location) {
        for (SseEmitter emitter : registry.connectionOf(deliveryId)) {
            try {
                emitter.send(location);
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
