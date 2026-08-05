package com.turkey.quick.order.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.dto.AddressRequest;
import com.turkey.quick.order.dto.ContactRequest;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.rider.service.RiderDeliveryRequestService;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * 배송요청 취소(#47)의 <b>HTTP 계층</b>을 검증한다.
 *
 * <p>서비스·통합 테스트가 트랜잭션·동시성·소유권 판단을 이미 보므로({@code DeliveryServiceTest},
 * {@code DeliveryCancelIntegrationTest}), 여기서는 그 층이 볼 수 없는 것만 본다:
 *
 * <ul>
 *   <li>{@code CustomerWebMvcConfig}에 {@code /api/customer/deliveries/*}/cancel} 등록이 실제로
 *       걸리는지 — 빠지면 세션이 있어도 {@code @RequestAttribute} 바인딩이 깨져 항상 실패한다
 *       (이번에 구현하며 실제로 빠져있던 걸 발견해 추가했다).
 *   <li>성공이 200인지, 실패가 각각 404/409로 정확히 매핑되는지.
 * </ul>
 *
 * <p>주문 픽스처는 생성 API의 HTTP 경로가 아니라 {@link DeliveryService#createDelivery}를 직접
 * 호출해 만든다 — 생성 자체의 HTTP 계약은 {@code CustomerDeliveryCreateE2ETest}가 이미 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerDeliveryCancelE2ETest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private static final AddressRequest PICKUP = new AddressRequest(
            "서울 강남구 테헤란로 152", "5층", "06236",
            new BigDecimal("37.5006"), new BigDecimal("127.0366"));

    private static final AddressRequest DESTINATION = new AddressRequest(
            "서울 강남구 강남대로 396", "1층", "06232",
            new BigDecimal("37.4979"), new BigDecimal("127.0276"));

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private RiderDeliveryRequestService riderDeliveryRequestService;

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

    private Long saveCustomerWithWallet(String loginId, String rawPassword, String phoneNumber, long balance) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member customer = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            wallet.credit(balance);
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    private RiderProfile saveAvailableRider() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member member = memberRepository.save(Member.create(
                    "cancel_e2e_rider_" + suffix, "hash", "라이더", "011" + suffix, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            profile.goOnline();
            return riderProfileRepository.save(profile);
        });
    }

    private String loginAndGetSessionCookie(String loginId, String rawPassword) {
        var response = rest.postForEntity("/api/customer/login",
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

    private long serverFare() {
        return deliveryService.quoteFare(
                new FareQuoteRequest(ItemType.DOCUMENT, PICKUP, DESTINATION)).fare().totalFare();
    }

    private Long createWaitingOrder(Long customerId, long fare) {
        DeliveryCreateRequest request = new DeliveryCreateRequest(
                UUID.randomUUID().toString(), fare, ItemType.DOCUMENT, PICKUP, DESTINATION,
                new ContactRequest("김고객", "010-1234-5678"),
                new ContactRequest("이수령", "010-9876-5432"));
        return deliveryService.createDelivery(request, customerId).deliveryId();
    }

    private String cancelUrl(Long deliveryId) {
        return "/api/customer/deliveries/" + deliveryId + "/cancel";
    }

    private long balanceOf(Long memberId) {
        return pointWalletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401을 반환한다")
    void returnsUnauthorizedWithoutSession() {
        saveActiveFarePolicy();
        Long customerId = saveCustomerWithWallet("e2e_cancel01", "p@ssw0rd", "01011112222", 50_000L);
        Long orderId = createWaitingOrder(customerId, serverFare());

        // CustomerWebMvcConfig에 "/api/customer/deliveries/*/cancel" 등록이 빠지면 여기가
        // 401이 아니라 @RequestAttribute 바인딩 실패로 깨진 응답 모양의 다른 오류가 난다.
        var response = rest.exchange(cancelUrl(orderId), HttpMethod.PATCH,
                withCookie(null, Map.of()), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("WAITING 주문을 취소하면 200과 함께 환급된다")
    void cancelsWaitingOrderAndRefunds() {
        saveActiveFarePolicy();
        Long customerId = saveCustomerWithWallet("e2e_cancel02", "p@ssw0rd", "01022223333", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_cancel02", "p@ssw0rd");
        long fare = serverFare();
        Long orderId = createWaitingOrder(customerId, fare);

        var response = rest.exchange(cancelUrl(orderId), HttpMethod.PATCH,
                withCookie(cookie, Map.of("reason", "단순 변심")), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("deliveryId", orderId.intValue())
                .containsEntry("status", "CANCELED");
        assertThat(balanceOf(customerId)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("이미 배차된 주문을 취소하려 하면 409를 반환한다")
    void returnsConflictWhenAlreadyAssigned() {
        saveActiveFarePolicy();
        Long customerId = saveCustomerWithWallet("e2e_cancel03", "p@ssw0rd", "01033334444", 50_000L);
        String cookie = loginAndGetSessionCookie("e2e_cancel03", "p@ssw0rd");
        long fare = serverFare();
        Long orderId = createWaitingOrder(customerId, fare);

        RiderProfile rider = saveAvailableRider();
        riderDeliveryRequestService.acceptDeliveryRequest(
                new AuthenticatedRider(rider.getMemberId(), "rider", "라이더", OperatingStatus.AVAILABLE),
                orderId);

        var response = rest.exchange(cancelUrl(orderId), HttpMethod.PATCH,
                withCookie(cookie, Map.of()), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf(customerId)).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("타인의 주문을 취소하려 하면 404를 반환한다")
    void returnsNotFoundForOtherCustomersOrder() {
        saveActiveFarePolicy();
        Long ownerId = saveCustomerWithWallet("e2e_cancel04_owner", "p@ssw0rd", "01044445555", 50_000L);
        Long orderId = createWaitingOrder(ownerId, serverFare());
        saveCustomerWithWallet("e2e_cancel04_stranger", "p@ssw0rd", "01055556666", 50_000L);
        String strangerCookie = loginAndGetSessionCookie("e2e_cancel04_stranger", "p@ssw0rd");

        var response = rest.exchange(cancelUrl(orderId), HttpMethod.PATCH,
                withCookie(strangerCookie, Map.of()), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
