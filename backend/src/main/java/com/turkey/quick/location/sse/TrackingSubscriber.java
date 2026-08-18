package com.turkey.quick.location.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 으로 팬아웃된 위치 이벤트를 받아 <b>이 인스턴스가 들고 있는</b> 연결로만 보낸다(#317).
 *
 * <p>모든 인스턴스가 같은 패턴을 구독하므로 모든 이벤트를 받는다. 그중 자기 레지스트리에 연결이
 * 있는 배송만 실제로 전송하고, 나머지는 맵 조회 한 번으로 끝난다 — 그게 팬아웃의 전부다.
 * {@code SseEmitter} 는 그 JVM 의 열린 응답에 묶여 있어 다른 인스턴스가 대신 보낼 방법이 없다.
 *
 * <p><b>페이로드를 파싱하지 않고 그대로 흘려보낸다.</b> 발행 쪽에서 만든 JSON 문자열을 SSE
 * {@code data:} 에 그대로 쓴다. 역직렬화→재직렬화가 사라지고, 페이로드 계약이 발행 지점 한 곳에만
 * 존재한다. 대가는 롤링 배포 중 구·신 형식이 그대로 클라이언트로 나갈 수 있다는 것이라,
 * <b>필드 추가만 허용하고 제거·의미 변경은 하지 않는다</b>(Flyway, Redis 값 형식에 이은 세 번째
 * 배포 호환성 표면).
 *
 * <p><b>배송(채널)당 동시 전송을 1개로 제한한다</b>(#566 재검증에서 드러난 문제의 수정,
 * 2026-08-18). {@code RedisMessageListenerConfig} 의 전역 {@code Semaphore(1000)}은 배송을
 * 구분하지 않아, 느린 클라이언트가 물린 배송 하나에 초당 수백 건이 계속 들어오면 그 메시지마다
 * permit 을 쥔 가상 스레드가 쌓여 — 그 배송과 전혀 무관한 다른 모든 배송까지 permit 을 못
 * 구해 굶는 문제가 실측으로 확인됐다(느린 클라이언트 5개, 60초 동안 무관한 배송 이벤트 0건
 * 수신, `docs/loadtest/2026-08-18-sse-fanout-virtual-thread-verification.md`). 같은 배송에
 * 대한 이전 전송이 아직 안 끝났으면 새 메시지는 permit 을 오래 붙잡지 않고 즉시 버린다 —
 * 위치 데이터는 최신 값만 의미 있다는 기존 철학(#297/#391)과 같다. 이렇게 하면 배송 하나가
 * 아무리 빨리 메시지를 받아도 전역 permit 을 오래 붙잡는 건 그 배송당 최대 1개뿐이라,
 * 정체된 배송이 몇 개든 나머지 배송에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class TrackingSubscriber implements MessageListener {

    private final SseRelay sseRelay;
    private final Counter coalesced;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public TrackingSubscriber(SseRelay sseRelay, MeterRegistry registry) {
        this.sseRelay = sseRelay;
        this.coalesced = Counter.builder("sse.fanout.coalesced")
                .description("같은 배송에 대한 이전 전송이 아직 끝나지 않아 건너뛴 이벤트")
                .register(registry);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Optional<Long> deliveryId = TrackingChannel.deliveryIdOf(channel);
        if (deliveryId.isEmpty()) {
            // 패턴에 걸렸는데 파싱이 안 되면 채널 명명 규칙이 어긋난 것이다. 던지지 않는다 —
            // 컨테이너가 삼켜 로그만 남기고, 그러면 원인을 찾기 더 어려워진다.
            log.warn("event=SSE_EVENT_DROPPED channel={} reason={}", channel, "UNPARSEABLE_CHANNEL");
            return;
        }
        Long id = deliveryId.get();
        if (!inFlight.add(id)) {
            coalesced.increment();
            return;
        }
        try {
            sseRelay.publish(id, new String(message.getBody(), StandardCharsets.UTF_8));
        } finally {
            inFlight.remove(id);
        }
    }
}
