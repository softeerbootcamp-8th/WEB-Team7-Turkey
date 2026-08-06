package com.turkey.quick.order.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 진행 중 배송 요약 조회의 <b>HTTP 계층</b>을 검증한다(#100).
 *
 * <p>서비스 단위 테스트({@code ActiveDeliveryQueryServiceTest})가 매핑·빈 결과를 보므로, 여기서는
 * 그 층이 볼 수 없는 것만 본다:
 *
 * <ul>
 *   <li>세션 인터셉터가 이 경로({@code /api/customer/deliveries/active})에 실제로 걸리는지 —
 *       {@code CustomerWebMvcConfig} 의 {@code /api/customer/deliveries/*} 패턴이 이 정적 경로도
 *       덮으므로 쿠키 없이 호출하면 401 이어야 한다(등록 누락 회귀).
 *   <li>정적 경로 {@code /active} 가 {@code /{deliveryId}}(상세) 보다 먼저 매칭되는지 — 진행 중
 *       배송이 실제로 요약으로 돌아오면 라우팅 우선순위가 맞다는 뜻이다("active" 가 Long 으로
 *       파싱되려 했으면 여기가 깨진다).
 *   <li>진행 중 배송이 없을 때 200 + data=null 로 나가는지(이슈 #100 의 "빈 결과").
 * </ul>
 *
 * <p>로컬 Redis 없이 돌리기 위해 SessionStore 를 인메모리로 교체한다(다른 E2E 와 같은 이유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerActiveDeliveryE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final String DELIVERIES_ENDPOINT = "/api/customer/deliveries";
    private static final String QUOTE_ENDPOINT = "/api/customer/deliveries/quote";
    private static final String ACTIVE_ENDPOINT = "/api/customer/deliveries/active";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private static final Map<String, Object> PICKUP = Map.of(
            "roadAddress", "서울 강남구 테헤란로 152",
            "detailAddress", "5층 프론트데스크",
            "postalCode", "06236",
            "latitude", 37.5006,
            "longitude", 127.0366);

    private static final Map<String, Object> DESTINATION = Map.of(
            "roadAddress", "서울 강남구 강남대로 396",
            "detailAddress", "1층 로비",
            "postalCode", "06232",
            "latitude", 37.4979,
            "longitude", 127.0276);

    private static final Map<String, Object> SENDER = Map.of(
            "name", "김고객", "phoneNumber", "010-1234-5678");

    private static final Map<String, Object> RECIPIENT = Map.of(
            "name", "이수령", "phoneNumber", "010-9876-5432");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        InMemorySessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

    private void saveActiveFarePolicy() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FarePolicy policy = FarePolicy.create(
                    "v1", 3_000L, 100, 130L, 30_000, LocalDateTime.now().minusDays(1));
            policy.addSurcharge(ItemType.DOCUMENT, 1_000L);
            policy.activate();
            farePolicyRepository.save(policy);
        });
    }

    private void saveCustomerWithWallet(String loginId, String rawPassword, String phoneNumber,
                                        long balance) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Member customer = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber,
                    MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            if (balance > 0) {
                wallet.credit(balance);
            }
            pointWalletRepository.save(wallet);
        });
    }

    private String loginAndGetSessionCookie(String loginId, String rawPassword) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", rawPassword), ApiResponse.class);
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";")[0];
    }

    private <T> HttpEntity<T> withCookie(String cookie, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(body, headers);
    }

    private long quotedFare() {
        var response = rest.exchange(QUOTE_ENDPOINT, HttpMethod.POST,
                withCookie(null, Map.of(
                        "itemType", "DOCUMENT",
                        "pickupAddress", PICKUP,
                        "destinationAddress", DESTINATION)),
                ApiResponse.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        Map<?, ?> fare = (Map<?, ?>) data.get("fare");
        return ((Number) fare.get("totalFare")).longValue();
    }

    /** 진행 중(WAITING) 배송을 하나 만들어 둔다 — 화면이 하게 될 그대로 견적→생성 순서로. */
    private void createWaitingDelivery(String cookie) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestKey", UUID.randomUUID().toString());
        body.put("estimatedFare", quotedFare());
        body.put("itemType", "DOCUMENT");
        body.put("pickup", PICKUP);
        body.put("destination", DESTINATION);
        body.put("sender", SENDER);
        body.put("recipient", RECIPIENT);

        var created = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, body), ApiResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401을 반환한다")
    void returnsUnauthorizedWithoutSession() {
        var response = rest.exchange(ACTIVE_ENDPOINT, HttpMethod.GET,
                withCookie(null, null), ApiResponse.class);

        // CustomerWebMvcConfig 의 /api/customer/deliveries/* 가 이 경로를 덮지 못하면 여기가 깨진다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("진행 중 배송이 있으면 200과 함께 그 배송 요약을 반환한다")
    void returnsActiveSummaryWhenInProgressExists() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_active01", "p@ssw0rd", "01011112222", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_active01", "p@ssw0rd");
        createWaitingDelivery(cookie);

        var response = rest.exchange(ACTIVE_ENDPOINT, HttpMethod.GET,
                withCookie(cookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "WAITING")
                .containsEntry("itemType", "DOCUMENT")
                .containsEntry("pickupRoadAddress", PICKUP.get("roadAddress"))
                .containsEntry("destinationRoadAddress", DESTINATION.get("roadAddress"))
                .hasEntrySatisfying("deliveryId", id -> assertThat(id).isNotNull());
    }

    @Test
    @DisplayName("진행 중 배송이 없으면 200과 함께 data가 null이다")
    void returnsNullDataWhenNoActiveDelivery() {
        saveCustomerWithWallet("e2e_active02", "p@ssw0rd", "01022223333", 0L);
        String cookie = loginAndGetSessionCookie("e2e_active02", "p@ssw0rd");

        var response = rest.exchange(ACTIVE_ENDPOINT, HttpMethod.GET,
                withCookie(cookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isNull();
    }
}
