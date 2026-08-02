package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.sse.SseRegistry;
import com.turkey.quick.location.sse.SseRelay;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 HTTP로 구독해 연결이 진짜로 열리고, 끊기면 정리되는지 확인한다(#289).
 * 세션·소유권·상태 검증(#291)이 붙은 뒤에는 {@link TrackingFixture}로 실제 배송을 만들어
 * 검증한다 — 임의의 배송 ID로는 이제 404/409에 막혀 레지스트리까지 도달하지 못한다.
 *
 * <p>{@code MockMvc}가 아니라 {@code RANDOM_PORT} + 진짜 {@code HttpClient}를 쓰는 이유는
 * SSE가 실제 소켓 스트림이라, 가짜 요청/응답으로는 "연결이 열려 있다"·"끊겼다"를 재현할 수 없기
 * 때문이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerTrackingStreamE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";

    @LocalServerPort
    private int port;

    @Autowired
    private SseRegistry registry;

    @Autowired
    private SseRelay sseRelay;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    @Test
    @DisplayName("본인 소유의 배차된 배송을 구독하면 event-stream 응답을 받고 레지스트리에 등록된다")
    void subscribingRegistersConnection() throws IOException, InterruptedException {
        TrackingFixture.Scenario scenario = fixture.assignedDelivery();
        String cookie = login(scenario.customerLoginId());

        HttpResponse<InputStream> response = openStream(scenario.deliveryId(), cookie);
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
            assertThat(registry.connectionOf(scenario.deliveryId())).hasSize(1);
        } finally {
            response.body().close();
        }
    }

    @Test
    @DisplayName("연결이 끊긴 뒤 위치가 전송되면(쓰기 실패) 레지스트리에서 정리된다")
    void disconnectingCleansUpRegistry() throws IOException, InterruptedException {
        TrackingFixture.Scenario scenario = fixture.assignedDelivery();
        String cookie = login(scenario.customerLoginId());

        HttpResponse<InputStream> response = openStream(scenario.deliveryId(), cookie);
        assertThat(registry.connectionOf(scenario.deliveryId())).hasSize(1);

        response.body().close();
        // heartbeat가 없어(위치 추적 단순화, #297) 서버는 클라이언트가 닫은 것을 스스로 알아채지
        // 못한다 — 다음 쓰기 시도가 실패해야 registry.remove가 불린다(SseRelay#publish). 그래서
        // 클라이언트 close만으로는 정리되지 않고, 실제 위치 전송을 한 번 트리거해야 한다.
        sseRelay.publish(scenario.deliveryId(), new LocationPayload(
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), Instant.now(), null));

        awaitUntilEmpty(scenario.deliveryId());
    }

    @Test
    @DisplayName("서로 다른 배송의 연결은 섞이지 않는다")
    void keepsDifferentDeliveriesIsolated() throws IOException, InterruptedException {
        TrackingFixture.Scenario first = fixture.assignedDelivery();
        TrackingFixture.Scenario second = fixture.assignedDelivery();

        HttpResponse<InputStream> firstResponse = openStream(first.deliveryId(), login(first.customerLoginId()));
        HttpResponse<InputStream> secondResponse = openStream(second.deliveryId(), login(second.customerLoginId()));
        try {
            assertThat(registry.connectionOf(first.deliveryId())).hasSize(1);
            assertThat(registry.connectionOf(second.deliveryId())).hasSize(1);
        } finally {
            firstResponse.body().close();
            secondResponse.body().close();
        }
    }

    @Test
    @DisplayName("타인의 배송을 구독하면 404를 반환한다")
    void rejectsOtherCustomersDelivery() throws IOException, InterruptedException {
        TrackingFixture.Scenario owner = fixture.assignedDelivery();
        TrackingFixture.Scenario stranger = fixture.assignedDelivery();
        String strangerCookie = login(stranger.customerLoginId());

        HttpResponse<String> response = openStreamExpectingError(owner.deliveryId(), strangerCookie);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(registry.connectionOf(owner.deliveryId())).isEmpty();
    }

    @Test
    @DisplayName("아직 배차되지 않은(WAITING) 배송을 구독하면 409를 반환한다")
    void rejectsNotYetAssignedDelivery() throws IOException, InterruptedException {
        TrackingFixture.Scenario scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);
        String cookie = login(scenario.customerLoginId());

        HttpResponse<String> response = openStreamExpectingError(scenario.deliveryId(), cookie);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("완료된 배송을 구독하면 409를 반환한다")
    void rejectsCompletedDelivery() throws IOException, InterruptedException {
        TrackingFixture.Scenario scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);
        String cookie = login(scenario.customerLoginId());

        HttpResponse<String> response = openStreamExpectingError(scenario.deliveryId(), cookie);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("세션 쿠키 없이 구독하면 401을 반환한다")
    void rejectsWithoutSessionCookie() throws IOException, InterruptedException {
        TrackingFixture.Scenario scenario = fixture.assignedDelivery();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri(scenario.deliveryId()))
                .GET()
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private String login(String loginId) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        return setCookie.split(";")[0];
    }

    private URI streamUri(long deliveryId) {
        return URI.create("http://localhost:%d/api/customer/deliveries/%d/tracking/stream"
                .formatted(port, deliveryId));
    }

    private HttpResponse<InputStream> openStream(long deliveryId, String cookie) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri(deliveryId))
                .header(HttpHeaders.COOKIE, cookie)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 인가 실패처럼 스트림이 아니라 평범한 JSON 오류 응답이 올 것으로 예상되는 호출에 쓴다. */
    private HttpResponse<String> openStreamExpectingError(long deliveryId, String cookie)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri(deliveryId))
                .header(HttpHeaders.COOKIE, cookie)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 이 저장소는 Awaitility를 쓰지 않으므로, 비동기 정리(onTimeout/onCompletion)를 짧게 폴링한다. */
    private void awaitUntilEmpty(long deliveryId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(2).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (registry.connectionOf(deliveryId).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(registry.connectionOf(deliveryId)).isEmpty();
    }
}
