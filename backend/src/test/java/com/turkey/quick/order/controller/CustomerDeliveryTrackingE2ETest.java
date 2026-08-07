package com.turkey.quick.order.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * #79 완료 조건을 사용자 시나리오로 검증한다. 실제 HTTP + 실제 Docker MySQL 을 지난다.
 *
 * <p>여기서만 잡히는 것: <b>{@code CustomerWebMvcConfig} 에 경로를 등록했는지.</b> 이 저장소는
 * Spring Security 를 쓰지 않아 인증이 선언적으로 걸리지 않으므로, 등록을 빠뜨리면 이 API 가
 * <b>인증 없이 열린 채로</b> 배포된다. 그래서 "쿠키 없이 401" 을 회귀 테스트로 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("GET /api/customer/deliveries/{deliveryId}/tracking")
class CustomerDeliveryTrackingE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    private static String trackingUrl(Long deliveryId) {
        return "/api/customer/deliveries/%d/tracking".formatted(deliveryId);
    }

    private String loginAndGetSessionCookie(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("로그인이 세션 쿠키를 내려야 한다").isNotNull();
        return setCookie.split(";")[0];
    }

    private org.springframework.http.ResponseEntity<JsonNode> get(Long deliveryId, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(trackingUrl(deliveryId), HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    @DisplayName("본인의 배차된 주문이면 200 과 스냅샷을 준다")
    void returnsSnapshot() {
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("success").asBoolean()).isTrue();

        JsonNode data = body.get("data");
        assertThat(data.get("deliveryId").asLong()).isEqualTo(scenario.deliveryId());
        assertThat(data.get("status").asText()).isEqualTo("DELIVERING");
        assertThat(data.get("riderName").asText()).isEqualTo("홍라이더");
        assertThat(data.get("riderPhoneNumber").asText()).isNotBlank();
        assertThat(data.get("totalFare").asLong()).isEqualTo(scenario.totalFare());
        assertThat(data.get("steps")).hasSize(5);
    }

    @Test
    @DisplayName("경로 서버가 죽어 있어도 200 이고, ETA 만 null 이다")
    void respondsWithoutRoutingServer() {
        // #421 완료 조건. 이 프로파일의 routing.base-url 은 아무도 듣지 않는 주소(localhost:1)라
        // 실제로 연결이 거부된다 — 즉 "OSRM 장애" 를 흉내가 아니라 그대로 재현한다.
        // 라이더 최신 위치도 Redis 에 없으므로(FLUSHDB 후) 산정 자체가 두 겹으로 불가능한 상황이다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("estimatedArrivalAt").isNull()).isTrue();
        // 나머지 화면 요소는 그대로 있어야 한다 — 부분 실패지 화면 실패가 아니다.
        assertThat(data.get("status").asText()).isEqualTo("DELIVERING");
        assertThat(data.get("riderName").asText()).isNotBlank();
        assertThat(data.get("totalFare").asLong()).isEqualTo(scenario.totalFare());
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401 이다")
    void rejectsRequestWithoutSessionCookie() {
        // 이 테스트가 실패하면 CustomerWebMvcConfig 등록이 빠졌다는 뜻이다 — 인증 없이 남의 주문
        // 상세(라이더 이름·연락처)를 읽을 수 있는 상태가 된다.
        var scenario = fixture.assignedDelivery();

        var response = get(scenario.deliveryId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("오류 본문은 ApiResponse JSON 이어야 한다").isTrue();
    }

    @Test
    @DisplayName("라이더 세션으로는 조회할 수 없다")
    void rejectsRiderSession() {
        var scenario = fixture.assignedDelivery();
        String riderCookie = loginAndGetSessionCookie(RIDER_LOGIN, scenario.riderLoginId());

        assertThat(get(scenario.deliveryId(), riderCookie).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 고객의 주문은 404 다")
    void rejectsOtherCustomersDelivery() {
        var target = fixture.assignedDelivery();
        var intruder = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, intruder.customerLoginId());

        assertThat(get(target.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("완료된 배송은 409 다 — 스트림과 같은 판정")
    void rejectsCompletedDelivery() {
        var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        assertThat(get(scenario.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
