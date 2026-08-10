package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * #311 완료 조건을 사용자 시나리오로 검증한다(고객 위치 폴링, 부하테스트용 실험 arm).
 *
 * <p>인가 게이트는 {@code CustomerDeliveryTrackingE2ETest}(#79)와 같은
 * {@code DeliveryTrackingAccessService}를 공유하므로 404/409 케이스는 그쪽과 같은 시맨틱이다. 여기서
 * 추가로 보는 것은 이 경로만의 것 — <b>Redis 위치 유무에 따른 응답 분기</b>와
 * <b>{@code CustomerWebMvcConfig} 경로 등록 여부</b>(쿠키 없이 401)다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("GET /api/customer/deliveries/{deliveryId}/location")
class CustomerLocationPollingE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private RiderLocationRepository riderLocationRepository;

    private static String locationUrl(Long deliveryId) {
        return "/api/customer/deliveries/%d/location".formatted(deliveryId);
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
        return rest.exchange(locationUrl(deliveryId), HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    @DisplayName("라이더 위치가 있으면 200 과 함께 좌표를 준다")
    void returnsLocationWhenPresent() {
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        LocationPayload location = new LocationPayload(new BigDecimal("37.4979"),
                new BigDecimal("127.0276"), Instant.parse("2026-08-03T01:02:03.456Z"), new BigDecimal("12.5"));
        riderLocationRepository.saveIfNewer(scenario.riderId(), location);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("deliveryId").asLong()).isEqualTo(scenario.deliveryId());
        assertThat(data.get("status").asText()).isEqualTo("DELIVERING");
        assertThat(data.get("location").get("latitude").asText()).isEqualTo("37.4979");
        assertThat(data.get("location").get("longitude").asText()).isEqualTo("127.0276");
    }

    @Test
    @DisplayName("라이더 위치가 없으면 오류가 아니라 200 과 location null 을 준다")
    void returnsNullLocationWhenAbsent() {
        // saveIfNewer 를 부르지 않았다 — 배차 직후라 아직 위치를 보내지 않은 상태를 흉내낸다.
        var scenario = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        var response = get(scenario.deliveryId(), cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(data.get("location").isNull()).isTrue();
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401 이다")
    void rejectsRequestWithoutSessionCookie() {
        // 실패하면 CustomerWebMvcConfig 등록이 빠졌다는 뜻이다 — 인증 없이 남의 배송 위치를 볼 수 있다.
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
    @DisplayName("다른 고객의 배송은 404 다")
    void rejectsOtherCustomersDelivery() {
        var target = fixture.assignedDelivery();
        var intruder = fixture.assignedDelivery();
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, intruder.customerLoginId());

        assertThat(get(target.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("배차 전(WAITING)은 409 다 — 스트림·스냅샷과 같은 판정")
    void rejectsWaitingDelivery() {
        var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        assertThat(get(scenario.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("완료된 배송은 409 다")
    void rejectsCompletedDelivery() {
        var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);
        String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, scenario.customerLoginId());

        assertThat(get(scenario.deliveryId(), cookie).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
