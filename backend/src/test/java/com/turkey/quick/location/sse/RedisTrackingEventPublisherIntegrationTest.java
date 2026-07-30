package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

/**
 * 발행 쪽만 검증한다 — 실제 MySQL 로 라이더의 진행 중 배송을 찾고, 실제 Redis 채널에 무엇이
 * 나가는지 임시 리스너로 받아 본다.
 *
 * <p>SSE 스트림을 거치지 않는 이유: 여기서 확인할 것은 <b>어느 채널로 어떤 문자열이 나가는가</b>이고,
 * 스트림까지 붙이면 실패했을 때 발행·수신 중 어디가 문제인지 가릴 수 없다. 수신은
 * {@code TrackingEventSubscriberE2ETest} 가 맡는다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("RedisTrackingEventPublisher")
class RedisTrackingEventPublisherIntegrationTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    @Autowired
    private RedisTrackingEventPublisher publisher;

    @Autowired
    private RedisMessageListenerContainer container;

    @Autowired
    private TrackingFixture fixture;

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private MessageListener listener;

    @AfterEach
    void removeListener() {
        if (listener != null) {
            container.removeMessageListener(listener);
            listener = null;
        }
    }

    /** 특정 주문 채널을 임시로 구독한다. 등록은 실제 SUBSCRIBE 왕복이 끝날 때까지 블록한다. */
    private void subscribeTo(Long orderId) {
        listener = (message, pattern) -> received.add(new String(message.getBody()));
        container.addMessageListener(listener, new ChannelTopic(TrackingChannel.of(orderId)));
    }

    private static RiderLocationSnapshot location(String latitude) {
        return new RiderLocationSnapshot(
                new BigDecimal(latitude), new BigDecimal("127.0276"),
                LocalDateTime.of(2026, 7, 30, 2, 0), new BigDecimal("12.5"));
    }

    private String awaitPayload() throws InterruptedException {
        String payload = received.poll(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(payload).as("발행된 페이로드를 %s 안에 받지 못했다", AWAIT).isNotNull();
        return payload;
    }

    @Nested
    @DisplayName("발행 대상 찾기")
    class Targeting {

        @Test
        @DisplayName("배차된 라이더의 위치는 그 주문 채널로 나간다")
        void publishesToAssignedOrdersChannel() throws InterruptedException {
            var scenario = fixture.assignedDelivery();
            subscribeTo(scenario.deliveryId());

            boolean published = publisher.publish(scenario.riderId(), location("37.4979"));

            assertThat(published).as("진행 중 배송이 있으므로 발행된다").isTrue();
            // 좌표 자릿수는 RiderLocationSnapshot 이 DECIMAL(10,7)에 맞춰 정규화한다.
            assertThat(awaitPayload())
                    .contains("\"latitude\":37.4979000")
                    .contains("\"measuredAt\":\"2026-07-30T02:00:00\"");
        }

        @Test
        @DisplayName("추적 가능한 네 상태에서 모두 발행된다")
        void publishesInEveryTrackableStatus() throws InterruptedException {
            for (OrderStatus status : OrderStatus.trackableStatuses()) {
                var scenario = fixture.deliveryWithStatus(status);
                subscribeTo(scenario.deliveryId());

                assertThat(publisher.publish(scenario.riderId(), location("37.4979")))
                        .as("status=%s", status)
                        .isTrue();
                assertThat(awaitPayload()).as("status=%s 의 페이로드", status).isNotNull();

                removeListener();
            }
        }
    }

    @Nested
    @DisplayName("발행하지 않는 경우")
    class NotPublished {

        @Test
        @DisplayName("배차 전 라이더의 위치는 발행하지 않는다")
        void skipsRiderWithoutAssignment() {
            // AVAILABLE 라이더의 30초 주기 위치가 대부분 이 경로다.
            var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);

            assertThat(publisher.publish(scenario.riderId(), location("37.4979"))).isFalse();
        }

        @Test
        @DisplayName("배송이 완료되면 발행이 멈춘다")
        void stopsAfterCompletion() {
            // 이것이 채널 키를 라이더가 아니라 주문으로 고른 이유다. 라이더 키였다면 완료 후에도
            // 그 라이더의 다음 배송 경로가 이전 고객에게 계속 흘러 개인정보가 샌다. 상태 조건이
            // 붙은 조회라 "조용해지는 안전한 실패"가 된다.
            var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);

            assertThat(publisher.publish(scenario.riderId(), location("37.4979"))).isFalse();
        }

        @Test
        @DisplayName("존재하지 않는 라이더도 예외가 아니라 false 다")
        void toleratesUnknownRider() {
            // 발행 실패가 라이더의 위치 갱신 POST 를 실패시키면 안 된다.
            assertThat(publisher.publish(999_999L, location("37.4979"))).isFalse();
        }

    }
}
