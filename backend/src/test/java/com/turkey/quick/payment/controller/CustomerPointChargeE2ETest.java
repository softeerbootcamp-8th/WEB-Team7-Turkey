package com.turkey.quick.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.payment.service.MockPaymentGateway;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
 * 포인트 충전 준비·승인의 <b>HTTP 계층</b>을 검증한다(#32, #33).
 *
 * <p>서비스 테스트가 못 보는 것만 본다:
 *
 * <ul>
 *   <li>세션 인터셉터가 이 경로에 실제로 걸리는지 — {@code CustomerWebMvcConfig} 의
 *       {@code addPathPatterns} 에 {@code /api/customer/points/**} 가 빠지면 여기서 잡힌다.
 *       그 등록이 없으면 인증이 아니라 {@code @RequestAttribute} 바인딩이 깨진다.
 *   <li>서비스가 던진 예외가 {@code GlobalExceptionHandler} 를 거쳐 의도한 상태 코드가 되는지
 *   <li>PG 거절이 오류가 아니라 200 + FAILED 로 나가는지
 * </ul>
 *
 * <p>로컬 Redis 없이 돌리기 위해 SessionStore 를 인메모리로 교체한다(다른 E2E 와 같은 이유).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerPointChargeE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final String CHARGES_ENDPOINT = "/api/customer/points/charges";
    private static final long AMOUNT = 30_000L;
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

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

    /** 회원과 지갑을 한 트랜잭션에서 만든다(PointWallet 은 @MapsId 라 member 가 managed 여야 한다). */
    private void saveCustomerWithWallet(String loginId, String rawPassword, String phoneNumber) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Member customer = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber,
                    MemberRole.CUSTOMER));
            pointWalletRepository.save(PointWallet.create(customer));
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

    private long prepareCharge(String cookie) {
        var response = rest.exchange(CHARGES_ENDPOINT, HttpMethod.POST,
                withCookie(cookie, Map.of(
                        "chargeRequestKey", UUID.randomUUID().toString(),
                        "amount", AMOUNT,
                        "paymentMethod", "CARD")),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        return ((Number) data.get("pointChargeId")).longValue();
    }

    private String confirmEndpoint(long pointChargeId) {
        return CHARGES_ENDPOINT + "/" + pointChargeId + "/confirm";
    }

    private String cancelEndpoint(long pointChargeId) {
        return CHARGES_ENDPOINT + "/" + pointChargeId + "/cancel";
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 충전 준비는 401을 반환한다")
    void returnsUnauthorizedWhenPreparingWithoutSession() {
        var response = rest.exchange(CHARGES_ENDPOINT, HttpMethod.POST,
                withCookie(null, Map.of(
                        "chargeRequestKey", UUID.randomUUID().toString(),
                        "amount", AMOUNT,
                        "paymentMethod", "CARD")),
                ApiResponse.class);

        // CustomerWebMvcConfig 의 addPathPatterns 에 /api/customer/points/** 가 빠지면 여기가 깨진다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 충전 승인은 401을 반환한다")
    void returnsUnauthorizedWhenConfirmingWithoutSession() {
        var response = rest.exchange(confirmEndpoint(1L), HttpMethod.POST,
                withCookie(null, Map.of("amount", AMOUNT, "authToken", "mock_auth_e2e")),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("준비한 충전을 승인하면 200과 반영된 잔액을 반환한다")
    void confirmsPreparedChargeAndReturnsUpdatedBalance() {
        saveCustomerWithWallet("e2e_charge01", "p@ssw0rd", "01011112222");
        String cookie = loginAndGetSessionCookie("e2e_charge01", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);

        var response = rest.exchange(confirmEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, Map.of("amount", AMOUNT, "authToken", "mock_auth_e2e")),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "PAID")
                .containsEntry("approvedAmount", (int) AMOUNT)
                .containsEntry("balanceAfter", (int) AMOUNT);
    }

    @Test
    @DisplayName("PG 가 거절하면 오류가 아니라 200과 FAILED 를 반환한다")
    void returnsOkWithFailedStatusWhenGatewayDeclines() {
        saveCustomerWithWallet("e2e_charge02", "p@ssw0rd", "01022223333");
        String cookie = loginAndGetSessionCookie("e2e_charge02", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);

        var response = rest.exchange(confirmEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, Map.of(
                        "amount", AMOUNT,
                        "authToken", MockPaymentGateway.DECLINE_TOKEN)),
                ApiResponse.class);

        // 예외로 처리하면 트랜잭션이 롤백되어 FAILED 전이 자체가 사라진다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "FAILED")
                .containsEntry("balanceAfter", 0);
    }

    @Test
    @DisplayName("결제 금액이 요청 금액과 다르면 400을 반환한다")
    void returnsBadRequestWhenAmountMismatches() {
        saveCustomerWithWallet("e2e_charge03", "p@ssw0rd", "01033334444");
        String cookie = loginAndGetSessionCookie("e2e_charge03", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);

        var response = rest.exchange(confirmEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, Map.of("amount", 100L, "authToken", "mock_auth_e2e")),
                ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 충전 취소는 401을 반환한다")
    void returnsUnauthorizedWhenCancelingWithoutSession() {
        var response = rest.exchange(cancelEndpoint(1L), HttpMethod.POST,
                withCookie(null, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("준비한 충전을 취소하면 200과 CANCELED, 변하지 않은 잔액을 반환한다")
    void cancelsPreparedChargeAndKeepsBalance() {
        saveCustomerWithWallet("e2e_cancel01", "p@ssw0rd", "01044445555");
        String cookie = loginAndGetSessionCookie("e2e_cancel01", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);

        var response = rest.exchange(cancelEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "CANCELED")
                .containsEntry("balance", 0)
                .containsEntry("requestedAmount", (int) AMOUNT);
    }

    @Test
    @DisplayName("이미 취소한 충전을 다시 취소해도 200과 CANCELED 를 반환한다")
    void repeatedCancelReturnsOk() {
        saveCustomerWithWallet("e2e_cancel02", "p@ssw0rd", "01055556666");
        String cookie = loginAndGetSessionCookie("e2e_cancel02", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);
        rest.exchange(cancelEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, null), ApiResponse.class);

        var response = rest.exchange(cancelEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, null), ApiResponse.class);

        // 따닥 클릭·재시도가 오류로 보이지 않아야 한다 — confirm 의 PAID 재요청과 같은 판단(#34)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "CANCELED");
    }

    @Test
    @DisplayName("이미 승인된 충전을 취소하면 409를 반환한다")
    void returnsConflictWhenCancelingPaidCharge() {
        saveCustomerWithWallet("e2e_cancel03", "p@ssw0rd", "01066667777");
        String cookie = loginAndGetSessionCookie("e2e_cancel03", "p@ssw0rd");
        long pointChargeId = prepareCharge(cookie);
        rest.exchange(confirmEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, Map.of("amount", AMOUNT, "authToken", "mock_auth_e2e")),
                ApiResponse.class);

        var response = rest.exchange(cancelEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(cookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("남의 충전 건을 취소하려 하면 403을 반환한다")
    void returnsForbiddenWhenCancelingOthersCharge() {
        saveCustomerWithWallet("e2e_cancel04", "p@ssw0rd", "01077778888");
        saveCustomerWithWallet("e2e_cancel05", "p@ssw0rd", "01088889999");
        String ownerCookie = loginAndGetSessionCookie("e2e_cancel04", "p@ssw0rd");
        String strangerCookie = loginAndGetSessionCookie("e2e_cancel05", "p@ssw0rd");
        long pointChargeId = prepareCharge(ownerCookie);

        var response = rest.exchange(cancelEndpoint(pointChargeId), HttpMethod.POST,
                withCookie(strangerCookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 충전 건을 취소하면 404를 반환한다")
    void returnsNotFoundWhenCancelingUnknownCharge() {
        saveCustomerWithWallet("e2e_cancel06", "p@ssw0rd", "01099990000");
        String cookie = loginAndGetSessionCookie("e2e_cancel06", "p@ssw0rd");

        var response = rest.exchange(cancelEndpoint(999_999L), HttpMethod.POST,
                withCookie(cookie, null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
