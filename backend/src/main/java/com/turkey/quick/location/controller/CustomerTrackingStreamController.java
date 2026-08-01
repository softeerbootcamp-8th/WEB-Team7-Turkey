package com.turkey.quick.location.controller;

import com.turkey.quick.location.sse.SseRegistry;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CustomerTrackingStreamController implements CustomerTrackingStreamApi {
    private static final Long TTL = 1000L;
    private final SseRegistry registry;

    @Override
    public SseEmitter subscribeTracking(Long deliveryId) {
        SseEmitter emitter = new SseEmitter(TTL);

        registry.add(deliveryId, emitter);
        emitter.onCompletion(() -> registry.remove(deliveryId, emitter));
        emitter.onTimeout(() -> registry.remove(deliveryId, emitter));
        emitter.onError(throwable -> registry.remove(deliveryId, emitter));

        // HTTP는 상태·헤더를 보낸 뒤엔 못 바꾼다. 아무것도 안 쓴 채로 타임아웃이 나면
        // 스프링이 응답을 커밋한 적이 없다고 보고 그제서야 503을 처음이자 마지막 응답으로
        // 내보낸다 — 그래서 등록 직후 한 번은 반드시 써서 응답을 실제로 열어야 한다.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.debug("event=SSE_INITIAL_SEND_FAILED deliveryId={}", deliveryId, e);
            registry.remove(deliveryId, emitter);
        }

        return emitter;
    }
}
