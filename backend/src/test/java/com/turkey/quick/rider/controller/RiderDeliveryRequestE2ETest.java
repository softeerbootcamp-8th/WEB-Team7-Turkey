package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
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
import java.util.List;
import java.util.Map;
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
 * #55 「퀵 요청 목록 보기」의 완료 조건(정상 흐름 + 예외 흐름)을 실제 HTTP 로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryRequestE2ETest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/api/rider/requests";
    private static final String LOGIN_ENDPOINT = "/api/rider/login";
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
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private InMemorySessionStore sessionStore;

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

    /** Member 저장과 RiderProfile(@MapsId) 저장을 한 트랜잭션으로 묶는다(RiderLoginE2ETest와 같은 이유). */
    private Member saveRider(String loginId, String rawPassword, String phoneNumber, boolean available) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (available) {
                profile.goOnline();
            }
            riderProfileRepository.save(profile);
            return member;
        });
    }

    private String loginAndGetSessionCookie(String loginId, String rawPassword) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", rawPassword), ApiResponse.class);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        return setCookie.split(";")[0];
    }

    private HttpEntity<Void> withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(headers);
    }

    private void saveWaitingOrderWithFareSnapshot() {
        FarePolicy policy = farePolicyRepository.save(
                FarePolicy.create("v1", 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
        Member customer = memberRepository.save(
                Member.create("e2e_rider_requests_customer", "hash", "고객", "01044445555", MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-e2e-55", ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", new BigDecimal("37.5010000"), new BigDecimal("127.0010000")),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, policy, FareType.ESTIMATE, "v1", 1000, 3000L, 130L, 0L));
    }

    @Test
    void AVAILABLE_라이더가_조회하면_200과_WAITING_목록을_반환한다() {
        saveRider("e2e_rider_requests01", "p@ssw0rd", "01022223333", true);
        saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests01", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        List<?> data = (List<?>) response.getBody().data();
        assertThat(data).isNotEmpty();
    }

    @Test
    void 세션_쿠키가_없으면_401을_반환한다() {
        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_운행_상태가_AVAILABLE이_아니면_403을_반환한다() {
        saveRider("e2e_rider_requests02", "p@ssw0rd", "01055556666", false);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests02", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().success()).isFalse();
    }
}
