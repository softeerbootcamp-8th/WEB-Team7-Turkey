package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.SseTestClient;
import com.turkey.quick.support.TrackingFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pub/Sub <b>수신단</b>만 검증한다 — 테스트가 직접 채널에 발행하고, 열린 SSE 스트림에 도달하는지 본다.
 *
 * <p>이 층을 따로 두는 이유: 다음 커밋(#78 발행)과 그 뒤의 2인스턴스 팬아웃 테스트가 실패했을 때
 * <b>원인이 배선인지 인스턴스 경계인지</b> 즉시 가릴 수 있어야 한다. 여기가 통과하면 배선(패턴
 * 구독·리스너·전송)은 맞다는 뜻이다.
 *
 * <p><b>이 테스트는 팬아웃을 증명하지 않는다.</b> 인스턴스가 1대면 Pub/Sub 을 통째로 빼고 직접
 * 전달해도 통과한다. 그 증명은 {@code TrackingFanoutMultiInstanceE2ETest} 가 한다.
 *
 * <p>⚠️ Redis Pub/Sub 은 <b>로직 DB 로 격리되지 않는다</b>(채널은 {@code SELECT} 를 무시한다).
 * 테스트는 DB 1, 개발용 앱은 DB 0 을 쓰지만 <b>채널은 공유된다.</b> 게다가
 * {@code DatabaseCleaner} 의 TRUNCATE 가 AUTO_INCREMENT 를 리셋해 테스트 주문이 매번 낮은 id 를
 * 받으므로, 개발용 앱을 띄운 채 테스트를 돌리면 채널명이 겹칠 수 있다(#82 에서 물린 것과 같은
 * 계열). 그래서 단언을 좌표까지 정확히 일치시킨다 — 남의 이벤트로 통과하지 않게.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("위치 이벤트 수신 배선")
class TrackingEventSubscriberE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String streamUrl(Long deliveryId) {
        return "http://localhost:%d/api/customer/deliveries/%d/tracking/stream".formatted(port, deliveryId);
    }

    private String loginAndGetSessionCookie(String loginId) {
        var response = rest.postForEntity(CUSTOMER_LOGIN,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
    }

    private static String locationPayload(String latitude) {
        return """
                {"latitude":%s,"longitude":127.0276000,"measuredAt":"2026-07-30T02:00:00","accuracyMeters":12.50}"""
                .formatted(latitude);
    }

    @Test
    @DisplayName("주문 채널로 발행하면 그 주문을 구독한 스트림에 도달한다")
    void deliversPublishedEventToSubscriber() {
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(scenario.customerLoginId());

        try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);

            redisTemplate.convertAndSend(
                    TrackingChannel.of(scenario.deliveryId()), locationPayload("37.4979000"));

            // 좌표까지 정확히 일치시킨다 — 채널이 개발용 앱과 겹쳐도 남의 이벤트로 통과하지 않게.
            assertThat(client.awaitEvent("location", AWAIT))
                    .isEqualTo(locationPayload("37.4979000"));
        }
    }

    @Test
    @DisplayName("발행된 페이로드를 파싱하지 않고 그대로 흘려보낸다")
    void forwardsPayloadVerbatim() {
        // 역직렬화→재직렬화를 하지 않는다는 계약이다. 그래야 페이로드 형식이 발행 지점 한 곳에만
        // 존재하고, 필드가 추가돼도 수신단을 고칠 필요가 없다.
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(scenario.customerLoginId());
        String payloadWithUnknownField = """
                {"latitude":37.4979000,"longitude":127.0276000,"measuredAt":"2026-07-30T02:00:00","futureField":"x"}""";

        try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);

            redisTemplate.convertAndSend(TrackingChannel.of(scenario.deliveryId()), payloadWithUnknownField);

            assertThat(client.awaitEvent("location", AWAIT)).isEqualTo(payloadWithUnknownField);
        }
    }

    @Test
    @DisplayName("다른 주문 채널의 이벤트는 전달되지 않는다")
    void ignoresOtherOrdersEvents() {
        var mine = fixture.assignedDelivery();
        var other = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(mine.customerLoginId());

        try (var client = SseTestClient.get(streamUrl(mine.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);

            redisTemplate.convertAndSend(TrackingChannel.of(other.deliveryId()), locationPayload("38.0000000"));
            redisTemplate.convertAndSend(TrackingChannel.of(mine.deliveryId()), locationPayload("37.4979000"));

            // 내 것이 도착한 시점에 남의 것은 이미 지나갔어야 한다(같은 채널 패턴을 구독하므로
            // 둘 다 이 인스턴스에 도달하지만, 레지스트리 조회에서 걸러진다).
            assertThat(client.awaitEvent("location", AWAIT)).isEqualTo(locationPayload("37.4979000"));
            assertThat(client.receivedLines())
                    .noneMatch(line -> line.contains("38.0000000"));
        }
    }

    @Test
    @DisplayName("구독자 없는 이벤트와 깨진 채널명 뒤에도 팬아웃이 계속 동작한다")
    void keepsWorkingAfterUndeliverableEvents() {
        // 모든 인스턴스가 모든 이벤트를 받으므로 "구독자 없음"이 대부분의 이벤트가 타는 경로다.
        // 거기서 예외가 나면 컨테이너가 삼켜 원인 없는 유실이 되고, 그 뒤 이벤트까지 잃을 수 있다.
        //
        // 레지스트리 크기를 단언하지 않는 이유: emitter 레지스트리는 인메모리 싱글턴이라 같은
        // 컨텍스트의 앞 테스트가 남긴 연결이 살아 있다(IntegrationTestSupport 는 MySQL·Redis 만
        // 비운다). 내부 상태 대신 "그 뒤에도 전달된다"는 결과를 본다.
        var orphan = fixture.assignedDelivery();
        var mine = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(mine.customerLoginId());

        try (var client = SseTestClient.get(streamUrl(mine.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);

            // 구독자가 없는 주문
            redisTemplate.convertAndSend(TrackingChannel.of(orphan.deliveryId()), locationPayload("38.0000000"));
            // 패턴에는 걸리지만 뒷부분이 숫자가 아닌 채널
            redisTemplate.convertAndSend("tracking:order:not-a-number", locationPayload("39.0000000"));
            // 정상 이벤트
            redisTemplate.convertAndSend(TrackingChannel.of(mine.deliveryId()), locationPayload("37.4979000"));

            assertThat(client.awaitEvent("location", AWAIT)).isEqualTo(locationPayload("37.4979000"));
        }
    }
}
