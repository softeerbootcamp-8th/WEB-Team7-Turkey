package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
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

/**
 * 운행 상태 조회(#53) E2E. 실제 HTTP 로 이슈의 성공/예외 흐름을 통과시킨다. 쿠키 없이 호출하면 401 이
 * 나오는지도 한 케이스로 덮어, 인터셉터 {@code addPathPatterns} 등록 누락을 회귀로 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderOperatingStatusE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String OPERATING_STATUS_ENDPOINT = "/api/rider/operating-status";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Long saveRider(String loginId, String phone, OperatingStatus status) {
        return tx().execute(t -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "홍길동", phone, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (status == OperatingStatus.AVAILABLE) {
                profile.goOnline();
            } else if (status == OperatingStatus.BUSY) {
                profile.goOnline();
                profile.assign();
            }
            riderProfileRepository.save(profile);
            return member.getId();
        });
    }

    private Long saveAssignedOrder(Long riderId, String customerLoginId, String customerPhone) {
        return tx().execute(t -> {
            Member customer = memberRepository.save(
                    Member.create(customerLoginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "김고객", customerPhone, MemberRole.CUSTOMER));
            RiderProfile rider = riderProfileRepository.findById(riderId).orElseThrow();
            DeliveryOrder order = DeliveryOrder.request(customer, UUID.randomUUID().toString(),
                    ItemType.DOCUMENT, 1500,
                    Address.of("서울 강남대로 1", "101호", "06000", new BigDecimal("37.5000000"), new BigDecimal("127.0000000")),
                    Address.of("서울 테헤란로 2", "202호", "06001", new BigDecimal("37.5010000"), new BigDecimal("127.0300000")),
                    Contact.of("보내는이", "01011112222"),
                    Contact.of("받는이", "01033334444"));
            order.assign(rider);
            return deliveryOrderRepository.save(order).getId();
        });
    }

    private String loginAndGetSessionCookie(String loginId) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", "p@ssw0rd"), ApiResponse.class);
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";")[0];
    }

    private HttpEntity<Void> withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(headers);
    }

    @Test
    @DisplayName("BUSY 라이더가 운행 상태를 조회하면 상태와 진행 중 배송 식별자를 함께 받는다")
    void busyRiderSeesStatusAndCurrentDelivery() {
        Long riderId = saveRider("e2e_os_busy", "01000000011", OperatingStatus.BUSY);
        Long orderId = saveAssignedOrder(riderId, "e2e_os_busy_cust", "01000000012");
        String cookie = loginAndGetSessionCookie("e2e_os_busy");

        var response = rest.exchange(OPERATING_STATUS_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data.operatingStatus").isEqualTo("BUSY");
        // JSON 숫자는 기본적으로 Integer 로 역직렬화되므로 int 로 비교한다.
        assertThat(response.getBody()).extracting("data.currentDeliveryId").isEqualTo(orderId.intValue());
    }

    @Test
    @DisplayName("AVAILABLE 라이더가 조회하면 진행 중 배송 없이 상태만 받는다")
    void availableRiderSeesStatusOnly() {
        saveRider("e2e_os_avail", "01000000013", OperatingStatus.AVAILABLE);
        String cookie = loginAndGetSessionCookie("e2e_os_avail");

        var response = rest.exchange(OPERATING_STATUS_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data.operatingStatus").isEqualTo("AVAILABLE");
        assertThat(response.getBody()).extracting("data.currentDeliveryId").isNull();
    }

    @Test
    @DisplayName("세션 쿠키 없이 조회하면 401 로 거부된다")
    void withoutCookieReturns401() {
        var response = rest.exchange(OPERATING_STATUS_ENDPOINT, HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
