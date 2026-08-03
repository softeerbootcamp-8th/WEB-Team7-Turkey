package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

@ExtendWith(MockitoExtension.class)
@DisplayName("TrackingSubscriber")
class TrackingSubscriberTest {

    private static final String LOCATION_JSON =
            "{\"latitude\":37.4979,\"longitude\":127.0276,\"measuredAt\":\"2026-08-03T01:02:03.456Z\"}";

    @Mock
    private SseRelay sseRelay;

    private Message message(String channel, String body) {
        return new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("채널의 배송으로 원본 JSON 을 그대로 전달한다")
    void relaysRawJsonToChannelDelivery() {
        new TrackingSubscriber(sseRelay).onMessage(message("tracking:order:1024", LOCATION_JSON), null);

        // 역직렬화→재직렬화하지 않는다. 페이로드 계약이 발행 지점 한 곳에만 존재하도록 한 설계다.
        then(sseRelay).should().publish(1024L, LOCATION_JSON);
    }

    @Test
    @DisplayName("파싱할 수 없는 채널명은 예외 없이 버린다")
    void dropsUnparseableChannelWithoutThrowing() {
        // 리스너 스레드에서 던지면 RedisMessageListenerContainer 가 삼켜 로그만 남기고, 그
        // 메시지의 나머지 수신자까지 잃는다.
        Message broken = message("tracking:order:oops", LOCATION_JSON);
        TrackingSubscriber subscriber = new TrackingSubscriber(sseRelay);

        assertThatCode(() -> subscriber.onMessage(broken, null)).doesNotThrowAnyException();

        then(sseRelay).should(never()).publish(anyLong(), anyString());
    }

    @Test
    @DisplayName("본문이 비어 있어도 그대로 전달한다 — 내용을 해석하지 않는다")
    void relaysEmptyBodyAsIs() {
        // 구독자는 본문을 검사하지 않는다. 형식 책임은 발행 쪽에 있고, 여기서 걸러내면 롤링
        // 배포 중 새 필드가 추가된 페이로드를 구버전이 버리는 일이 생긴다.
        new TrackingSubscriber(sseRelay).onMessage(message("tracking:order:7", ""), null);

        then(sseRelay).should().publish(7L, "");
    }
}
