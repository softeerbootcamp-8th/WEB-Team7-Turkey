package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.location.service.RiderLocationStore;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.SseTestClient;
import com.turkey.quick.support.TrackingFixture;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * #77 완료 조건을 사용자 시나리오로 검증한다. 실제 HTTP + 실제 Docker MySQL·Redis 를 지난다.
 *
 * <p>스트림을 읽는 데 {@code TestRestTemplate} 을 쓰지 않는 이유는 {@link SseTestClient} javadoc 에
 * 적어 뒀다 — 본문을 끝까지 버퍼링해서 테스트가 emitter 타임아웃만큼 멈춘다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("GET /api/customer/deliveries/{deliveryId}/tracking/stream")
class CustomerTrackingStreamE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private RiderLocationStore riderLocationStore;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private String streamUrl(Long deliveryId) {
        return "http://localhost:%d/api/customer/deliveries/%d/tracking/stream".formatted(port, deliveryId);
    }

    private String loginAndGetSessionCookie(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("로그인이 세션 쿠키를 내려야 한다").isNotNull();
        return setCookie.split(";")[0];
    }

    private void saveRiderLocation(Long riderId, String latitude) {
        riderLocationStore.saveIfNewer(riderId, new RiderLocationSnapshot(
                new BigDecimal(latitude), new BigDecimal("127.0276"),
                LocalDateTime.of(2026, 7, 30, 2, 0), new BigDecimal("12.5")));
    }

    /** 오류 응답은 스트림이 아니라 JSON 한 덩이라 바로 끝난다. ApiResponse 형태인지 본다. */
    private String awaitErrorBody(SseTestClient client) {
        client.awaitLine(line -> line.contains("\"success\""), AWAIT, "ApiResponse 오류 본문");
        return String.join("", client.receivedLines());
    }

    @Nested
    @DisplayName("구독 성공")
    class Subscribed {

        @Test
        @DisplayName("배차된 본인 주문을 구독하면 현재 상태를 초기 이벤트로 받는다")
        void sendsInitEventWithStatus() {
            var scenario = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                assertThat(client.statusCode()).isEqualTo(200);

                String data = client.awaitEvent("init", AWAIT);

                assertThat(data)
                        .contains("\"deliveryId\":" + scenario.deliveryId())
                        .contains("\"status\":\"ASSIGNED\"");
            }
        }

        @Test
        @DisplayName("라이더 위치가 없으면 상태만 보낸다")
        void sendsInitEventWithoutLocation() {
            // 배차 직후이거나 Redis TTL(10분)이 지난 경우다. 이슈 예외 흐름이 정한 동작이다.
            var scenario = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                assertThat(client.awaitEvent("init", AWAIT)).contains("\"location\":null");
            }
        }

        @Test
        @DisplayName("라이더 최신 위치가 있으면 초기 이벤트에 실어 보낸다")
        void sendsInitEventWithLatestLocation() {
            var scenario = fixture.assignedDelivery();
            saveRiderLocation(scenario.riderId(), "37.4979");
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                String data = client.awaitEvent("init", AWAIT);

                // 좌표 자릿수는 RiderLocationSnapshot 이 DECIMAL(10,7)에 맞춰 정규화한다.
                assertThat(data)
                        .contains("\"latitude\":37.4979000")
                        .contains("\"measuredAt\":\"2026-07-30T02:00:00\"");
            }
        }

        @Test
        @DisplayName("재연결 간격을 서버가 지정해 보낸다")
        void sendsReconnectHint() {
            // retry 를 서버가 정한다. 이 값이 없으면 브라우저 기본값(3~5초, 구현마다 다름)에
            // 맡겨져 재연결 부하를 예측할 수 없다.
            var scenario = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                client.awaitLine(line -> line.startsWith("retry:"), AWAIT, "retry 필드");
            }
        }
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("세션 쿠키가 없으면 401이다")
        void rejectsRequestWithoutSessionCookie() {
            // 이 케이스가 CustomerWebMvcConfig 의 addPathPatterns 등록 누락을 잡는 회귀 테스트다.
            // 등록을 빠뜨리면 인터셉터가 안 걸리고, @RequestAttribute 가 필수라
            // ServletRequestBindingException 으로 500 이 된다(그것도 ApiResponse 형태가 아니다).
            var scenario = fixture.assignedDelivery();

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), null)) {
                assertThat(client.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }
        }

        @Test
        @DisplayName("Accept 가 text/event-stream 이어도 오류가 406으로 바뀌지 않는다")
        void keepsErrorStatusUnderEventStreamAccept() {
            // 응답에 Content-Type 을 명시하지 않으면 스프링이 Accept 로 컨텐트 협상을 하고,
            // JSON 컨버터가 안 걸려 HttpMediaTypeNotAcceptableException(406)을 던진다. 브라우저
            // EventSource 는 이 Accept 만 보내므로 401·409·429 가 전부 406 이 되어 상태코드와
            // ApiResponse 본문을 둘 다 잃는다. GlobalExceptionHandler 의 contentType 명시가
            // 그것을 막는데, 그 회귀를 여기서 잡는다.
            var scenario = fixture.assignedDelivery();

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), null)) {
                assertThat(client.statusCode())
                        .as("406 이면 Content-Type 명시가 빠진 것이다")
                        .isNotEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
                assertThat(awaitErrorBody(client)).contains("\"success\":false");
            }
        }

        @Test
        @DisplayName("라이더 세션으로는 구독할 수 없다")
        void rejectsRiderSession() {
            var scenario = fixture.assignedDelivery();
            String riderCookie = loginAndGetSessionCookie(RIDER_LOGIN, scenario.riderLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), riderCookie)) {
                assertThat(client.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }
        }
    }

    @Nested
    @DisplayName("구독 거부")
    class Rejected {

        @Test
        @DisplayName("다른 고객의 주문은 404다")
        void rejectsOtherCustomersDelivery() {
            var target = fixture.assignedDelivery();
            var intruder = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, intruder.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(target.deliveryId()), cookie)) {
                assertThat(client.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            }
        }

        @Test
        @DisplayName("배차 전 주문은 409다")
        void rejectsWaitingDelivery() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                assertThat(client.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
                assertThat(awaitErrorBody(client)).contains("\"success\":false");
            }
        }

        @Test
        @DisplayName("완료된 주문은 409다")
        void rejectsCompletedDelivery() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                assertThat(client.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            }
        }

        @Test
        @DisplayName("동시 연결 한도를 넘으면 429다")
        void rejectsBeyondConnectionLimit() {
            var scenario = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());
            List<SseTestClient> open = new ArrayList<>();
            try {
                for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
                    SseTestClient client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie);
                    open.add(client);
                    // init 을 받아 연결이 실제로 성립했음을 확인한 뒤 다음 연결을 연다.
                    client.awaitEvent("init", AWAIT);
                }

                try (var overflow = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                    assertThat(overflow.statusCode())
                            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
                }
            } finally {
                open.forEach(SseTestClient::close);
            }
        }
    }

    @Nested
    @DisplayName("연결 집계")
    class ConnectionAccounting {

        @Test
        @DisplayName("연결이 성립하면 Redis 집계에 기록된다")
        void recordsConnectionInSharedStore() {
            // 집계를 Redis 에 두는 이유는 같은 배송의 연결이 여러 인스턴스에 흩어져 로컬로는 총
            // 개수를 알 수 없기 때문이다(CLAUDE.md). 실제로 기록되는지 여기서 확인한다.
            var scenario = fixture.assignedDelivery();
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

            try (var client = SseTestClient.get(streamUrl(scenario.deliveryId()), cookie)) {
                client.awaitEvent("init", AWAIT);

                assertThat(redisTemplate.opsForZSet()
                        .zCard("tracking:order-connections:" + scenario.deliveryId()))
                        .isEqualTo(1);
            }
        }

        // "클라이언트가 끊으면 자리가 해제된다"는 여기 없다. SSE 는 쓰기를 시도할 때만 끊김을
        // 알 수 있고, heartbeat 가 붙기 전에는 아무것도 쓰지 않으므로 서버가 끊김을 인지하지
        // 못한다(emitter 타임아웃 5분까지). 그래서 그 검증은 heartbeat 와 함께 있어야 한다 —
        // TrackingHeartbeatE2ETest 참고. 그때까지는 ZSET stale 임계(45초)가 자리를 되찾는다.
    }

    @Nested
    @DisplayName("OpenAPI 노출")
    class OpenApiExposure {

        @Test
        @DisplayName("스트림 엔드포인트는 OpenAPI 스펙에 나타나지 않는다")
        void hidesStreamFromSpec() {
            // @Hidden 이 실제로 먹었는지 확인한다. 노출되면 응답 스키마가 SseEmitter 라는
            // 프레임워크 타입으로 새어 나가고, Orval 이 쓸 수 없는 훅을 만든다. springdoc 이
            // 인터페이스가 아니라 핸들러 클래스를 기준으로 볼 수 있어 양쪽에 달아 뒀는데,
            // 그게 통했는지는 실제 스펙을 봐야 안다.
            String spec = rest.getForObject("/v3/api-docs", String.class);

            assertThat(spec).doesNotContain("/tracking/stream");
        }
    }
}
