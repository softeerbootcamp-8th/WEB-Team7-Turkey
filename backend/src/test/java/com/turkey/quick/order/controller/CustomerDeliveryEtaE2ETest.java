package com.turkey.quick.order.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * #447 완료 조건을 사용자 시나리오로 검증한다. 실제 HTTP + 실제 Docker MySQL·Redis 를 지난다.
 *
 * <p>여기서만 잡히는 것 두 가지.
 *
 * <ul>
 *   <li><b>{@code CustomerWebMvcConfig} 등록 누락.</b> 이 저장소는 Spring Security 를 쓰지 않아
 *       인증이 선언적으로 걸리지 않는다 — 등록을 빠뜨리면 이 API 가 <b>인증 없이 열린 채로</b>
 *       배포된다. 그래서 "쿠키 없이 401" 을 회귀로 둔다.</li>
 *   <li><b>OSRM 이 닿지 않는 환경에서도 200 인지.</b> 이 프로파일의 {@code routing.base-url} 은
 *       아무도 듣지 않는 주소(localhost:1)라 실제로 연결이 거부된다 — "경로 서버 장애"를 흉내가
 *       아니라 그대로 재현한다. 폴링 경로라 그 상황이 <b>오류가 아니어야</b> 한다.</li>
 * </ul>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("GET /api/customer/deliveries/{deliveryId}/eta")
class CustomerDeliveryEtaE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    private static String etaUrl(Long deliveryId) {
        return "/api/customer/deliveries/%d/eta".formatted(deliveryId);
    }

    private String loginAndGetSessionCookie(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("로그인이 세션 쿠키를 내려야 한다").isNotNull();
        return setCookie.split(";")[0];
    }

    private ResponseEntity<JsonNode> get(Long deliveryId, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(etaUrl(deliveryId), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    @DisplayName("경로 서버가 죽어 있어도 200 이고 ETA 만 null 이다")
    void respondsWithoutRoutingServer() {
        // 폴링 경로의 핵심 계약이다 — 1분마다 도는 요청이 OSRM 장애로 오류를 내면 화면이 계속
        // 실패를 처리해야 한다. 상태는 그대로 내려가야 프론트가 사유를 구분할 수 있다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("success").asBoolean()).isTrue();

        JsonNode data = body.get("data");
        assertThat(data.get("deliveryId").asLong()).isEqualTo(scenario.deliveryId());
        assertThat(data.get("status").asText()).isEqualTo("DELIVERING");
        assertThat(data.get("estimatedArrivalAt").isNull()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"WAITING", "COMPLETED", "CANCELED"})
    @DisplayName("추적 불가 상태도 200 + null 이다 — 추적 스냅샷의 409 와 다르다")
    void returnsOkForNonTrackableStatus(OrderStatus status) {
        // 추적 스냅샷(#79)은 COMPLETED·CANCELED 를 409 로 막지만 이 API 는 막지 않는다(사람 확인).
        // WAITING 케이스는 left join fetch 회귀도 함께 막는다 — inner join 이면 404 가 된다.
        var scenario = fixture.deliveryWithStatus(status);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("status").asText()).isEqualTo(status.name());
        assertThat(data.get("estimatedArrivalAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401 이다")
    void rejectsRequestWithoutSessionCookie() {
        // 이 테스트가 실패하면 CustomerWebMvcConfig 등록이 빠졌다는 뜻이다.
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
        // 남의 배송이 지금 어디쯤인지가 ETA 로 새어 나가면 안 된다.
        var target = fixture.assignedDelivery();
        var intruder = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, intruder.customerLoginId());

        assertThat(get(target.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
