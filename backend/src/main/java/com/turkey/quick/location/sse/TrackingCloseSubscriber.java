package com.turkey.quick.location.sse;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 배송이 완료·취소됐을 때 <b>이 인스턴스가 들고 있는</b> 그 배송의 SSE 연결을 닫는다(#450).
 *
 * <p>{@link TrackingSubscriber} 와 형제 관계다 — 같은 팬아웃 구조를 쓰지만 채널이 다르다.
 * 완료 처리를 한 인스턴스와 고객 emitter 를 들고 있는 인스턴스가 다를 수 있어서, 종료도 데이터와
 * 똑같이 Pub/Sub 을 거쳐야 한다.
 *
 * <p><b>본문을 읽지 않는다.</b> 필요한 정보(배송 식별자)가 채널명에 이미 있다. 그래서 형제와 같은
 * "구독자는 파싱하지 않는다" 규약을 지킬 수 있고, 나중에 종료 사유를 본문에 담더라도 이 리스너를
 * 고치지 않아도 된다.
 *
 * <p><b>이 신호가 유실돼도 정합성은 깨지지 않는다.</b> emitter 절대 수명(5분)이 만료되면 같은
 * 사슬(재연결 → 409 → 영구 종료 → 재조회)이 성립한다. 이 신호는 그 5분을 재연결 왕복(약 3초)으로
 * 줄이는 최적화다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingCloseSubscriber implements MessageListener {

    private final SseRelay sseRelay;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Optional<Long> deliveryId = TrackingChannel.deliveryIdOfClose(channel);
        if (deliveryId.isEmpty()) {
            // 패턴에 걸렸는데 파싱이 안 되면 채널 명명 규칙이 어긋난 것이다. 형제와 같은 이유로
            // 던지지 않는다 — 컨테이너가 삼켜 로그만 남기고 원인 추적이 더 어려워진다.
            log.warn("event=SSE_CLOSE_DROPPED channel={} reason={}", channel, "UNPARSEABLE_CHANNEL");
            return;
        }
        sseRelay.closeAll(deliveryId.get());
    }
}
