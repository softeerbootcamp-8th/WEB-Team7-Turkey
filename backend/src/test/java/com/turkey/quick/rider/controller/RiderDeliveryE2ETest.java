package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired TestRestTemplate rest;
    @Autowired MemberRepository memberRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired DeliveryOrderRepository deliveryOrderRepository;
    @Autowired FarePolicyRepository farePolicyRepository;
    @Autowired OrderFareSnapshotRepository orderFareSnapshotRepository;
    @Autowired PointWalletRepository pointWalletRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("BUSY 라이더가 다시 접속하면 현재 배송 단계와 다음 행동을 조회한다")
    void shouldRestoreCurrentDeliveryAfterLogin() {
        Fixture fixture = saveAssignedOrder("e2e_rider_86_a", "01086100001");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        transition(cookie, fixture.orderId(), "START_MOVING_TO_PICKUP");

        var response = rest.exchange(currentEndpoint(), HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(cookie)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("deliveryId")).isEqualTo(fixture.orderId().intValue());
        assertThat(data.get("status")).isEqualTo("MOVING_TO_PICKUP");
        assertThat(data.get("nextAction")).isEqualTo("PICK_UP");
        assertThat(((Map<?, ?>) data.get("pickup")).get("detailAddress")).isEqualTo("상세");
        assertThat(((Map<?, ?>) data.get("sender")).get("phoneNumber")).isEqualTo("01011112222");
    }

    @Test
    @DisplayName("세션 쿠키 없이 현재 배송을 조회하면 401을 반환한다")
    void shouldRequireAuthenticationToGetCurrentDelivery() {
        var response = rest.exchange(currentEndpoint(), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BUSY 라이더에게 진행 배송이 없으면 409를 반환한다")
    void shouldRejectBusyRiderWithoutCurrentDelivery() {
        saveRider("e2e_rider_86_b", "01086100002", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_86_b", "p@ssw0rd");

        var response = rest.exchange(currentEndpoint(), HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(cookie)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("배정된 라이더가 픽업지 이동을 시작하면 200과 MOVING_TO_PICKUP 상태를 반환한다")
    void shouldStartMovingToPickup() {
        Fixture fixture = saveAssignedOrder("e2e_rider_58_a", "01058100001");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");

        var response = rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("MOVING_TO_PICKUP");
        assertThat(deliveryOrderRepository.findById(fixture.orderId()).orElseThrow().getMovingToPickupAt())
                .isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus().name())
                .isEqualTo("BUSY");
    }

    @Test
    @DisplayName("픽업지로 이동 중인 라이더가 픽업 완료를 요청하면 200과 PICKED_UP 상태를 반환한다")
    void shouldPickUp() {
        Fixture fixture = saveAssignedOrder("e2e_rider_59_a", "01059100001");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie, "START_MOVING_TO_PICKUP"), ApiResponse.class);

        var response = rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie, "PICK_UP"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("PICKED_UP");
        assertThat(deliveryOrderRepository.findById(fixture.orderId()).orElseThrow().getPickedUpAt())
                .isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus().name())
                .isEqualTo("BUSY");
    }

    @Test
    @DisplayName("동일한 픽업 완료 요청을 다시 보내면 409를 반환한다")
    void shouldRejectDuplicatePickUpRequest() {
        Fixture fixture = saveAssignedOrder("e2e_rider_59_b", "01059100002");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie, "START_MOVING_TO_PICKUP"), ApiResponse.class);
        rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie, "PICK_UP"), ApiResponse.class);

        var response = rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie, "PICK_UP"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("픽업 완료 주문의 배송을 시작하면 200과 DELIVERING 상태를 반환한다")
    void shouldStartDelivering() {
        Fixture fixture = saveAssignedOrder("e2e_rider_65_a", "01065100001");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        transition(cookie, fixture.orderId(), "START_MOVING_TO_PICKUP");
        transition(cookie, fixture.orderId(), "PICK_UP");

        var response = transition(cookie, fixture.orderId(), "START_DELIVERING");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("DELIVERING");
        assertThat(deliveryOrderRepository.findById(fixture.orderId()).orElseThrow().getDeliveringAt())
                .isNotNull();
    }

    @Test
    @DisplayName("완료 인증을 제출하면 배송 완료와 라이더 해제 및 정산 적립을 처리한다")
    void shouldCompleteDelivery() {
        Fixture fixture = saveAssignedOrder("e2e_rider_62_a", "01062100001");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        transition(cookie, fixture.orderId(), "START_MOVING_TO_PICKUP");
        transition(cookie, fixture.orderId(), "PICK_UP");
        transition(cookie, fixture.orderId(), "START_DELIVERING");

        var response = rest.exchange(completeEndpoint(fixture.orderId()), HttpMethod.POST,
                completeRequest(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("operatingStatus")).isEqualTo("AVAILABLE");
        assertThat(((Number) data.get("settlementAmount")).longValue()).isEqualTo(3100L);
        assertThat(pointWalletRepository.findByMemberId(fixture.riderId()).orElseThrow().getBalance())
                .isEqualTo(3100L);
    }

    @Test
    @DisplayName("완료 인증정보가 없으면 배송 완료 요청을 400으로 거부한다")
    void shouldRejectCompletionWithoutProof() {
        Fixture fixture = saveAssignedOrder("e2e_rider_62_b", "01062100002");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");

        var response = rest.exchange(completeEndpoint(fixture.orderId()), HttpMethod.POST,
                new HttpEntity<>(Map.of(), cookieHeaders(cookie)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("세션 쿠키 없이 픽업지 이동을 시작하면 401을 반환한다")
    void shouldRequireAuthentication() {
        var response = rest.exchange(endpoint(1L), HttpMethod.POST, request(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("동일한 픽업지 이동 시작 요청을 다시 보내면 409를 반환한다")
    void shouldRejectDuplicateRequest() {
        Fixture fixture = saveAssignedOrder("e2e_rider_58_b", "01058100002");
        String cookie = loginAndGetSessionCookie(fixture.loginId(), "p@ssw0rd");
        rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST, request(cookie), ApiResponse.class);

        var response = rest.exchange(endpoint(fixture.orderId()), HttpMethod.POST,
                request(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("다른 라이더의 주문을 변경하려 하면 403을 반환한다")
    void shouldRejectDifferentRider() {
        Fixture assigned = saveAssignedOrder("e2e_rider_58_c", "01058100003");
        saveRider("e2e_rider_58_d", "01058100004", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_58_d", "p@ssw0rd");

        var response = rest.exchange(endpoint(assigned.orderId()), HttpMethod.POST,
                request(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String endpoint(Long orderId) {
        return "/api/rider/deliveries/" + orderId + "/transition";
    }

    private String currentEndpoint() {
        return "/api/rider/deliveries/current";
    }

    private String completeEndpoint(Long orderId) {
        return "/api/rider/deliveries/" + orderId + "/complete";
    }

    private org.springframework.http.ResponseEntity<ApiResponse> transition(
            String cookie, Long orderId, String action) {
        return rest.exchange(endpoint(orderId), HttpMethod.POST,
                request(cookie, action), ApiResponse.class);
    }

    private HttpEntity<Map<String, String>> request(String cookie) {
        return request(cookie, "START_MOVING_TO_PICKUP");
    }

    private HttpEntity<Map<String, String>> request(String cookie, String action) {
        return new HttpEntity<>(Map.of("action", action), cookieHeaders(cookie));
    }

    private HttpEntity<Map<String, String>> completeRequest(String cookie) {
        return new HttpEntity<>(Map.of(
                "proofType", "PHOTO",
                "proofValue", "proof/e2e-62.jpg"), cookieHeaders(cookie));
    }

    private HttpHeaders cookieHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private String loginAndGetSessionCookie(String loginId, String password) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", password), ApiResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).getFirst().split(";")[0];
    }

    private Fixture saveAssignedOrder(String loginId, String phoneNumber) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RiderProfile rider = saveRider(loginId, phoneNumber, true);
            Member customer = memberRepository.save(Member.create(
                    "customer_" + loginId, "hash", "고객", "019" + phoneNumber.substring(3), MemberRole.CUSTOMER));
            DeliveryOrder order = DeliveryOrder.request(customer, "request-" + loginId,
                    ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지", "상세", "12345", new BigDecimal("37.5000000"),
                            new BigDecimal("127.0000000")),
                    Address.of("도착지", "상세", "54321", new BigDecimal("37.6000000"),
                            new BigDecimal("127.1000000")),
                    Contact.of("보내는 사람", "01011112222"), Contact.of("받는 사람", "01033334444"));
            order.assign(rider);
            DeliveryOrder saved = deliveryOrderRepository.save(order);
            FarePolicy policy = farePolicyRepository.save(
                    FarePolicy.create("v58-" + loginId, 3000L, 100, 100L, 30000,
                            LocalDateTime.now().minusDays(1)));
            orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                    saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(), 1000, 3000L, 100L, 0L));
            return new Fixture(rider.getMemberId(), saved.getId(), loginId);
        });
    }
    private RiderProfile saveRider(String loginId, String phoneNumber, boolean busy) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"),
                            "라이더", phoneNumber, MemberRole.RIDER));

            RiderProfile rider = RiderProfile.create(member);
            pointWalletRepository.save(PointWallet.create(member));
            rider.goOnline();

            if (busy) {
                rider.assign();
            }

            return riderProfileRepository.save(rider);
        });
    }

    private record Fixture(Long riderId, Long orderId, String loginId) {
    }
}
