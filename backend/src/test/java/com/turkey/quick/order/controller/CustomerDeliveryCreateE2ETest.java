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
 * 배송요청 생성의 <b>HTTP 계층</b>을 검증한다(#37, #40).
 *
 * <p>서비스·통합 테스트가 트랜잭션과 DB 제약을 보므로, 여기서는 그 층이 볼 수 없는 것만 본다:
 *
 * <ul>
 *   <li>세션 인터셉터가 이 경로에 실제로 걸리는지 — {@code CustomerWebMvcConfig} 는
 *       {@code /api/customer/deliveries/**} 로 넓히지 않고 <b>정확 경로</b>로 등록하므로, 빠뜨리면
 *       인증이 아니라 {@code @RequestAttribute} 바인딩이 먼저 깨진다.
 *   <li>형제 경로 {@code /quote}(비로그인 견적 API)가 여전히 쿠키 없이 열려 있는지 — 인증 패턴을
 *       실수로 넓히면 프론트 견적 화면이 조용히 깨지므로 회귀로 고정해 둔다.
 *   <li>서비스가 던진 예외가 {@code GlobalExceptionHandler} 를 거쳐 402·409 가 되는지.
 *       특히 <b>402 는 이 저장소에서 처음 쓰는 코드</b>라 실제로 그 상태로 나가는지 확인이 필요하다.
 *   <li>생성 성공이 201 인지({@code @ResponseStatus(CREATED)} 가 구현체가 아니라 인터페이스에 있다).
 * </ul>
 *
 * <p>요금 금액은 하드코딩하지 않는다. 먼저 {@code /quote} 를 호출해 서버가 말하는 금액을 받아
 * 그대로 {@code estimatedFare} 에 실어 보낸다 — 화면이 실제로 하게 될 흐름과 같고, 거리 계산이
 * 바뀌어도 이 테스트는 깨지지 않는다.
 *
 * <p>로컬 Redis 없이 돌리기 위해 SessionStore 를 인메모리로 교체한다(다른 E2E 와 같은 이유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerDeliveryCreateE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final String DELIVERIES_ENDPOINT = "/api/customer/deliveries";
    private static final String QUOTE_ENDPOINT = "/api/customer/deliveries/quote";
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

    /**
     * 활성 요금 정책. 이 저장소에는 fare_policy 시드 마이그레이션이 없고 {@code DatabaseCleaner} 가
     * 매 테스트 전에 비우므로 테스트마다 직접 만들어야 한다.
     */
    private void saveActiveFarePolicy() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FarePolicy policy = FarePolicy.create(
                    "v1", 3_000L, 100, 130L, 30_000, LocalDateTime.now().minusDays(1));
            policy.addSurcharge(ItemType.DOCUMENT, 1_000L);
            policy.activate();
            farePolicyRepository.save(policy);
        });
    }

    /** 회원과 지갑을 한 트랜잭션에서 만든다(PointWallet 은 @MapsId 라 member 가 managed 여야 한다). */
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

    /** 화면이 하게 될 그대로: 견적 API 로 금액을 받아 그 값을 생성 요청에 싣는다. */
    private long quotedFare() {
        var response = rest.exchange(QUOTE_ENDPOINT, HttpMethod.POST,
                withCookie(null, Map.of(
                        "itemType", "DOCUMENT",
                        "pickupAddress", PICKUP,
                        "destinationAddress", DESTINATION)),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        Map<?, ?> fare = (Map<?, ?>) data.get("fare");
        return ((Number) fare.get("totalFare")).longValue();
    }

    private Map<String, Object> createBody(String requestKey, long estimatedFare) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestKey", requestKey);
        body.put("estimatedFare", estimatedFare);
        body.put("itemType", "DOCUMENT");
        body.put("pickup", PICKUP);
        body.put("destination", DESTINATION);
        body.put("sender", SENDER);
        body.put("recipient", RECIPIENT);
        return body;
    }

    private long balanceOf(String loginId) {
        Long memberId = memberRepository.findByLoginId(loginId).orElseThrow().getId();
        return pointWalletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401을 반환한다")
    void returnsUnauthorizedWithoutSession() {
        saveActiveFarePolicy();

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(null, createBody(UUID.randomUUID().toString(), 6_400L)),
                ApiResponse.class);

        // CustomerWebMvcConfig 에 /api/customer/deliveries 등록이 빠지면 여기가 깨진다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("형제 경로인 요금 견적은 여전히 쿠키 없이 열려 있다")
    void quoteRemainsPublic() {
        saveActiveFarePolicy();

        // quotedFare() 자체가 쿠키 없이 200 을 기대한다. 인증 패턴을 넓히면 여기가 401 이 된다.
        assertThat(quotedFare()).isPositive();
    }

    @Test
    @DisplayName("주문을 생성하면 201과 함께 포인트가 차감된다")
    void createsDeliveryAndDeductsPoints() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order01", "p@ssw0rd", "01011112222", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_order01", "p@ssw0rd");
        long fare = quotedFare();
        String requestKey = UUID.randomUUID().toString();

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(requestKey, fare)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "WAITING")
                .containsEntry("requestKey", requestKey);
        assertThat(balanceOf("e2e_order01")).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("포인트가 모자라면 402를 반환하고 주문도 만들어지지 않는다")
    void returnsPaymentRequiredWhenPointsAreInsufficient() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order02", "p@ssw0rd", "01022223333", 1_000L);
        String cookie = loginAndGetSessionCookie("e2e_order02", "p@ssw0rd");
        long fare = quotedFare();

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(UUID.randomUUID().toString(), fare)),
                ApiResponse.class);

        // 이 저장소에서 402 를 쓰는 첫 API 다. 프론트가 message 파싱 없이 충전 화면으로 보낼 수 있어야 한다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().success()).isFalse();
        assertThat(balanceOf("e2e_order02")).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("화면이 안내한 요금이 서버 계산과 다르면 409를 반환하고 결제하지 않는다")
    void returnsConflictWhenFareChanged() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order03", "p@ssw0rd", "01033334444", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_order03", "p@ssw0rd");
        long fare = quotedFare();

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(UUID.randomUUID().toString(), fare - 500)),
                ApiResponse.class);

        // 5,000원인 줄 알고 눌렀는데 5,500원이 빠져나가는 상황을 막는 계약이다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf("e2e_order03")).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("같은 요청키로 재전송하면 같은 주문을 돌려주고 포인트는 한 번만 차감된다")
    void resendReturnsSameOrderWithoutChargingTwice() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order04", "p@ssw0rd", "01044445555", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_order04", "p@ssw0rd");
        long fare = quotedFare();
        String requestKey = UUID.randomUUID().toString();

        var first = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(requestKey, fare)), ApiResponse.class);
        var second = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(requestKey, fare)), ApiResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> firstData = (Map<?, ?>) first.getBody().data();
        Map<?, ?> secondData = (Map<?, ?>) second.getBody().data();
        assertThat(secondData.get("deliveryId")).isEqualTo(firstData.get("deliveryId"));
        assertThat(balanceOf("e2e_order04")).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("진행 중 배송요청이 있으면 새 요청은 409를 반환한다")
    void returnsConflictWhenAnotherDeliveryIsInProgress() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order05", "p@ssw0rd", "01055556666", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_order05", "p@ssw0rd");
        long fare = quotedFare();
        rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(UUID.randomUUID().toString(), fare)),
                ApiResponse.class);

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, createBody(UUID.randomUUID().toString(), fare)),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf("e2e_order05")).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("예상 요금이 없으면 400을 반환한다")
    void returnsBadRequestWhenEstimatedFareMissing() {
        saveActiveFarePolicy();
        saveCustomerWithWallet("e2e_order06", "p@ssw0rd", "01066667777", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_order06", "p@ssw0rd");
        Map<String, Object> body = createBody(UUID.randomUUID().toString(), 0L);
        body.put("estimatedFare", null);

        var response = rest.exchange(DELIVERIES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, body), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
