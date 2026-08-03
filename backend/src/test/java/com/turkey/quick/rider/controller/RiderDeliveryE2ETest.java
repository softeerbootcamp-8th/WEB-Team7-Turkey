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
    @Autowired PlatformTransactionManager transactionManager;

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

    private HttpEntity<Map<String, String>> request(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(Map.of("action", "START_MOVING_TO_PICKUP"), headers);
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
