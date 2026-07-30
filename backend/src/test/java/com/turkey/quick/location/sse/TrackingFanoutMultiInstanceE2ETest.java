package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.SecondaryInstance;
import com.turkey.quick.support.SseTestClient;
import com.turkey.quick.support.TrackingFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>이 기능의 핵심을 증명하는 유일한 자동 검증이다.</b>
 *
 * <p>인스턴스 A 에 고객 SSE 를 연결하고, <b>인스턴스 B</b> 에 라이더 위치를 POST 해서 A 의 스트림에
 * 이벤트가 도달하는지 본다. B 는 그 주문의 {@code SseEmitter} 를 갖고 있지 않으므로
 * <b>Redis Pub/Sub 팬아웃 말고는 어떤 구현으로도 통과할 수 없다.</b> 나머지 모든 테스트는 1대에서
 * 돌기 때문에 팬아웃을 빼고 직접 전달해도 통과한다.
 *
 * <p>역방향(A 에 POST, A 에서 수신)만 있으면 의미가 없다. 교차 케이스가 전부다.
 *
 * <p>두 번째 인스턴스를 {@code @BeforeAll} 에서 띄우는 이유: {@code IntegrationTestSupport} 의
 * {@code @BeforeEach} 가 MySQL·Redis 를 비우므로 <b>픽스처는 그 뒤에</b> 만들어야 한다. 상속상
 * 부모의 {@code @BeforeEach} 가 먼저 돌아 자연히 만족된다. B 는 상태를 캐싱하지 않으므로 미리
 * 띄워 둬도 무방하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("Pub/Sub 팬아웃 (2인스턴스)")
class TrackingFanoutMultiInstanceE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";
    private static final String RIDER_LOCATION = "/api/rider/location";
    private static final Duration AWAIT = Duration.ofSeconds(10);

    private static SecondaryInstance secondary;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private TrackingEmitterRegistry registryA;

    @BeforeAll
    static void startSecondInstance() {
        secondary = SecondaryInstance.start();
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondary != null) {
            secondary.close();
        }
    }

    private String streamUrlOnA(Long deliveryId) {
        return "http://localhost:%d/api/customer/deliveries/%d/tracking/stream".formatted(port, deliveryId);
    }

    /**
     * 로그인은 인스턴스 A 에서 하고 그 쿠키를 B 에도 쓴다. 세션이 Redis 에 있으므로 그대로
     * 통해야 한다 — <b>스티키 세션이 필요 없다는 것</b>이 여기서 부수적으로 증명된다.
     */
    private String loginOnA(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        assertThat(response.getStatusCode()).as("인스턴스 A 로그인").isEqualTo(HttpStatus.OK);
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
    }

    private HttpHeaders withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    private Map<String, Object> locationBody(double latitude, Instant measuredAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("latitude", latitude);
        body.put("longitude", 127.0276);
        body.put("measuredAt", measuredAt.toString());
        body.put("accuracyMeters", 12.5);
        return body;
    }

    @Test
    @DisplayName("B 에 올린 위치가 A 에 연결된 고객 스트림으로 전달된다")
    void deliversAcrossInstances() {
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            client.awaitEvent("init", AWAIT);

            // 인스턴스 A 만 이 주문의 emitter 를 갖고 있다. 이 전제가 깨지면 아래 단언이
            // 팬아웃을 증명하지 못한다.
            assertThat(registryA.size()).as("A 가 연결을 들고 있다").isEqualTo(1);
            assertThat(secondary.bean(TrackingEmitterRegistry.class).size())
                    .as("B 는 연결을 들고 있지 않다 — 레지스트리가 static 이면 여기서 깨진다")
                    .isZero();
            assertThat(secondary.bean(TrackingEmitterRegistry.class))
                    .as("두 인스턴스의 레지스트리는 서로 다른 객체여야 한다")
                    .isNotSameAs(registryA);

            // 라이더 위치를 인스턴스 B 로 보낸다.
            var posted = rest.exchange(secondary.url(RIDER_LOCATION), HttpMethod.POST,
                    new HttpEntity<>(locationBody(37.4979, Instant.now()), withCookie(riderCookie)),
                    ApiResponse.class);
            assertThat(posted.getStatusCode()).as("B 에서 위치 갱신").isEqualTo(HttpStatus.OK);

            // B 는 emitter 가 없다. 그래도 A 의 스트림에 도달해야 한다 — Pub/Sub 팬아웃뿐이다.
            assertThat(client.awaitEvent("location", AWAIT))
                    .contains("\"latitude\":37.4979000");
        }
    }

    @Test
    @DisplayName("A 에 올린 위치도 A 의 스트림으로 전달된다")
    void deliversWithinSameInstance() {
        // 같은 인스턴스 경로도 팬아웃을 지난다(자기 자신의 패턴 구독으로 되돌아온다). 이 케이스만
        // 있으면 "직접 전달" 구현도 통과하므로 위 교차 케이스와 함께여야 의미가 있다.
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            client.awaitEvent("init", AWAIT);

            rest.exchange(RIDER_LOCATION, HttpMethod.POST,
                    new HttpEntity<>(locationBody(37.4979, Instant.now()), withCookie(riderCookie)),
                    ApiResponse.class);

            assertThat(client.awaitEvent("location", AWAIT)).contains("\"latitude\":37.4979000");
        }
    }

    @Test
    @DisplayName("A 에서 만든 세션으로 B 의 API 를 그대로 쓸 수 있다")
    void sharesSessionAcrossInstances() {
        // 스티키 세션이 필요 없다는 것 — 세션이 Redis 에 있고 JVM 에 없다는 전제의 검증이다.
        var scenario = fixture.assignedDelivery();
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        var response = rest.exchange(secondary.url("/api/rider/session"), HttpMethod.GET,
                new HttpEntity<>(withCookie(riderCookie)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
