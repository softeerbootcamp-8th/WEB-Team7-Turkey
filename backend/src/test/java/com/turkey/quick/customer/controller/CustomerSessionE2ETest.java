package com.turkey.quick.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 테스트 대체로 교체한다
 * (CustomerLoginE2ETest와 동일한 이유).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerSessionE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final String SESSION_ENDPOINT = "/api/customer/session";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SessionStore sessionStore;

    private Member saveCustomer(String loginId, String rawPassword, String phoneNumber) {
        return memberRepository.save(
                Member.create(loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.CUSTOMER));
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

    @Test
    @DisplayName("유효한 세션 쿠키면 200과 고객 정보를 반환한다")
    void shouldReturnCustomerForValidSessionCookie() {
        saveCustomer("e2e_session01", "p@ssw0rd", "01011112222");
        String cookie = loginAndGetSessionCookie("e2e_session01", "p@ssw0rd");

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("loginId", "e2e_session01");
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401을 반환한다")
    void shouldReturnUnauthorizedWithoutSessionCookie() {
        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 401을 반환한다")
    void shouldReturnUnauthorizedForUnknownSession() {
        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie("SESSION_ID=no-such-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("만료된 세션이면 401과 함께 쿠키를 만료시키는 응답을 반환한다")
    void shouldExpireCookieForExpiredSession() {
        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie("SESSION_ID=expired-or-unknown-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        assertThat(setCookie).contains("SESSION_ID=");
        assertThat(setCookie).containsIgnoringCase("Max-Age=0");
    }

    @Test
    @DisplayName("로그인 이후 탈퇴한 계정이면 401을 반환한다")
    void shouldReturnUnauthorizedForAccountWithdrawnAfterLogin() {
        Member member = saveCustomer("e2e_session02", "p@ssw0rd", "01022223333");
        String cookie = loginAndGetSessionCookie("e2e_session02", "p@ssw0rd");

        member.withdraw();
        memberRepository.save(member);

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("라이더 세션으로 고객 세션 확인을 시도하면 401을 반환한다")
    void shouldReturnUnauthorizedForRiderSession() {
        Member rider = memberRepository.save(
                Member.create("e2e_rider_session", PASSWORD_ENCODER.encode("p@ssw0rd"), "라이더", "01033334444", MemberRole.RIDER));
        String sessionId = "rider-session-id";
        sessionStore.create(sessionId, rider.getId(), "RIDER", java.time.Duration.ofHours(2));

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie("SESSION_ID=" + sessionId), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
