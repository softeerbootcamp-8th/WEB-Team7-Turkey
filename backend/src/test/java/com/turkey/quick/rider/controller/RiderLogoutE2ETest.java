package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
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
 * 라이더 로그아웃 E2E(#51). 실제 HTTP로 이슈의 성공/예외 흐름을 그대로 통과시킨다.
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 대체로 교체한다(RiderLoginE2ETest와 동일).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderLogoutE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String LOGOUT_ENDPOINT = "/api/rider/logout";
    private static final String SESSION_ENDPOINT = "/api/rider/session";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

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

    /** Member + RiderProfile(@MapsId)를 한 트랜잭션에서 만들고, 원하는 운행 상태까지 전이시킨다. */
    private Long saveRiderWithStatus(String loginId, String phoneNumber, OperatingStatus status) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "홍길동", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member); // UNAVAILABLE
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
    @DisplayName("AVAILABLE 라이더가 로그아웃하면 200·만료 쿠키를 받고, 상태는 UNAVAILABLE이 되며 세션 확인은 401이 된다")
    void availableRiderLogoutSucceedsAndBecomesUnavailable() {
        Long memberId = saveRiderWithStatus("e2e_logout_avail", "01011112222", OperatingStatus.AVAILABLE);
        String cookie = loginAndGetSessionCookie("e2e_logout_avail");

        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0)).containsIgnoringCase("Max-Age=0");
        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.UNAVAILABLE);

        var afterLogout = rest.exchange(SESSION_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BUSY 라이더가 로그아웃하면 409로 거부되고, 상태와 세션이 그대로 유지된다")
    void busyRiderLogoutIsRejected() {
        saveRiderWithStatus("e2e_logout_busy", "01022223333", OperatingStatus.BUSY);
        String cookie = loginAndGetSessionCookie("e2e_logout_busy");

        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // 세션이 유지되므로 로그아웃 후에도 세션 확인이 성공한다.
        var sessionCheck = rest.exchange(SESSION_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);
        assertThat(sessionCheck.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더가 로그아웃하면 200을 받고 세션 확인은 401이 된다")
    void unavailableRiderLogoutSucceeds() {
        saveRiderWithStatus("e2e_logout_unavail", "01033334444", OperatingStatus.UNAVAILABLE);
        String cookie = loginAndGetSessionCookie("e2e_logout_unavail");

        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var afterLogout = rest.exchange(SESSION_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("세션 쿠키가 없어도 로그아웃은 200을 반환한다")
    void logoutWithoutCookieReturns200() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("존재하지 않는 세션이어도 로그아웃은 200을 반환한다")
    void logoutWithUnknownSessionReturns200() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST,
                withCookie("SESSION_ID=no-such-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
