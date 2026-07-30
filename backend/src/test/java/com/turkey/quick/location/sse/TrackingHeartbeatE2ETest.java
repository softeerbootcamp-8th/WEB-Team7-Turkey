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
 * heartbeat 의 실제 주기 동작을 검증한다.
 *
 * <p><b>간격을 200ms 로 줄여서 돈다.</b> 운영값은 15초인데 그대로 기다리면 테스트 하나가 15초를
 * 쓴다. 값의 출처는 {@code TrackingStreamPolicy.HEARTBEAT_INTERVAL_MILLIS} 하나이고
 * ({@code TrackingHeartbeatScheduler} 가 그것을 프로퍼티 기본값으로 이어 붙인다) 여기서는 그
 * 프로퍼티만 덮는다 — 즉 <b>이 테스트가 통과한다는 것은 배선이 맞다는 뜻이지 운영 간격이
 * 15초라는 뜻은 아니다.</b> 그 값 자체는 사람이 확정한 정책이다.
 *
 * <p>stale 임계(45초)는 덮지 않는다. 200ms 간격이면 갱신이 훨씬 잦아 그대로 유효하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"spring.autoconfigure.exclude=",
                              "tracking.sse.heartbeat-interval-ms=200"})
@ActiveProfiles("integration")
@DisplayName("SSE heartbeat")
class TrackingHeartbeatE2ETest extends IntegrationTestSupport {

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

    private static String connectionKey(Long deliveryId) {
        return "tracking:order-connections:" + deliveryId;
    }

    private long connectionCount(Long deliveryId) {
        Long count = redisTemplate.opsForZSet().zCard(connectionKey(deliveryId));
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("조용한 스트림에도 주석 프레임이 계속 흐른다")
    void keepsQuietStreamAlive() {
        // 위치 이벤트가 하나도 없는 상태다. 정지한 라이더의 좌표는 DUPLICATE 로 판정돼 발행조차
        // 되지 않으므로 실제 운행에서 흔히 이 상태가 된다 — 이것 없이는 CloudFront 오리진 응답
        // 타임아웃에 연결이 끊긴다.
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(scenario.customerLoginId());

        try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);

            // 주석 프레임은 콜론으로 시작한다. EventSource 가 무시하므로 프론트에 핸들러가 필요 없다.
            client.awaitLine(line -> line.startsWith(":"), AWAIT, "heartbeat 주석 프레임");
        }
    }

    @Test
    @DisplayName("클라이언트가 끊으면 heartbeat 가 감지해 연결 자리를 해제한다")
    void reclaimsSlotOfDisconnectedClient() {
        // SSE 는 쓰기를 시도할 때만 끊김을 알 수 있다. heartbeat 가 없으면 이 정리가 emitter
        // 타임아웃(5분)까지 일어나지 않아, 탭을 닫고 바로 다시 여는 동작이 429 를 받는다.
        // (이 검증이 커밋 4 에서 실패했던 이유가 정확히 그것이다.)
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(scenario.customerLoginId());

        try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
            client.awaitEvent("init", AWAIT);
            assertThat(connectionCount(scenario.deliveryId())).isEqualTo(1);
        }

        awaitConnectionCountZero(scenario.deliveryId());
    }

    @Test
    @DisplayName("끊긴 뒤 곧바로 다시 구독할 수 있다")
    void allowsImmediateResubscribeAfterDisconnect() {
        // 상한(3)을 채운 뒤 전부 닫고 곧바로 다시 여는 시나리오다. heartbeat 정리가 없으면
        // stale 임계(45초)까지 기다려야 해서 상한의 의미가 "동시 3개"에서 "45초당 3개"로 바뀐다.
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(scenario.customerLoginId());

        for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                client.awaitEvent("init", AWAIT);
            }
            awaitConnectionCountZero(scenario.deliveryId());
        }

        try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
            assertThat(client.statusCode()).isEqualTo(200);
            client.awaitEvent("init", AWAIT);
        }
    }

    private void awaitConnectionCountZero(Long deliveryId) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (connectionCount(deliveryId) == 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기가 중단됐습니다", e);
            }
        }
        throw new AssertionError("끊긴 뒤에도 연결 기록이 남아 있습니다. count="
                + connectionCount(deliveryId));
    }
}
