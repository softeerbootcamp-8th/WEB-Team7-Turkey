package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.sse.SseRegistry;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 HTTP로 구독해 연결이 진짜로 열리고, 끊기면 정리되는지 확인한다(#289).
 *
 * <p>{@code MockMvc}가 아니라 {@code RANDOM_PORT} + 진짜 {@code HttpClient}를 쓰는 이유는
 * SSE가 실제 소켓 스트림이라, 가짜 요청/응답으로는 "연결이 열려 있다"·"끊겼다"를 재현할 수 없기
 * 때문이다.
 *
 * <p>이 컨트롤러 자체는 아직 세션을 전혀 보지 않는다(인증은 {@code #291}). 그런데도 로그인이
 * 필요한 이유는, 이 경로가 {@code CustomerWebMvcConfig}에 이미 등록돼 있어서다 — 나중에 같은
 * 경로에 인증이 붙을 걸 미리 등록해 둔 것이라, 지금은 그 인터셉터를 통과할 자격(유효한 세션
 * 쿠키)만 있으면 컨트롤러까지 도달한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerTrackingStreamE2ETest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @LocalServerPort
    private int port;

    @Autowired
    private SseRegistry registry;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("구독하면 event-stream 응답을 받고 레지스트리에 등록된다")
    void subscribingRegistersConnection() throws IOException, InterruptedException {
        long deliveryId = 1L;
        String cookie = loginAndGetSessionCookie("e2e_tracking01", "p@ssw0rd");

        HttpResponse<InputStream> response = openStream(deliveryId, cookie);
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
            assertThat(registry.connectionOf(deliveryId)).hasSize(1);
        } finally {
            response.body().close();
        }
    }

    @Test
    @DisplayName("연결이 끊기면 레지스트리에서 정리된다")
    void disconnectingCleansUpRegistry() throws IOException, InterruptedException {
        long deliveryId = 2L;
        String cookie = loginAndGetSessionCookie("e2e_tracking02", "p@ssw0rd");

        HttpResponse<InputStream> response = openStream(deliveryId, cookie);
        assertThat(registry.connectionOf(deliveryId)).hasSize(1);

        response.body().close();

        awaitUntilEmpty(deliveryId);
    }

    @Test
    @DisplayName("서로 다른 배송의 연결은 섞이지 않는다")
    void keepsDifferentDeliveriesIsolated() throws IOException, InterruptedException {
        long firstDeliveryId = 3L;
        long secondDeliveryId = 4L;
        String cookie = loginAndGetSessionCookie("e2e_tracking03", "p@ssw0rd");

        HttpResponse<InputStream> first = openStream(firstDeliveryId, cookie);
        HttpResponse<InputStream> second = openStream(secondDeliveryId, cookie);
        try {
            assertThat(registry.connectionOf(firstDeliveryId)).hasSize(1);
            assertThat(registry.connectionOf(secondDeliveryId)).hasSize(1);
        } finally {
            first.body().close();
            second.body().close();
        }
    }

    private Member saveCustomer(String loginId, String rawPassword) {
        String phoneNumber = "010%08d".formatted(Math.abs(loginId.hashCode()) % 100_000_000);
        return memberRepository.save(Member.create(
                loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.CUSTOMER));
    }

    private String loginAndGetSessionCookie(String loginId, String rawPassword) {
        saveCustomer(loginId, rawPassword);
        var response = rest.postForEntity("/api/customer/login",
                Map.of("loginId", loginId, "password", rawPassword), ApiResponse.class);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        return setCookie.split(";")[0];
    }

    private HttpResponse<InputStream> openStream(long deliveryId, String cookie) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/customer/deliveries/%d/tracking/stream"
                        .formatted(port, deliveryId)))
                .header(HttpHeaders.COOKIE, cookie)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
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
