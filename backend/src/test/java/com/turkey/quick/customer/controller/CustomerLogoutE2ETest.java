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
import org.springframework.http.HttpMethod;
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
class CustomerLogoutE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final String LOGOUT_ENDPOINT = "/api/customer/logout";
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
    @DisplayName("로그인 후 로그아웃하면 200과 만료 쿠키를 반환하고 세션이 삭제된다")
    void shouldExpireCookieAndDeleteSessionOnLogout() {
        saveCustomer("e2e_logout01", "p@ssw0rd", "01011112222");
        String cookie = loginAndGetSessionCookie("e2e_logout01", "p@ssw0rd");
        String sessionId = cookie.substring("SESSION_ID=".length());
        assertThat(sessionStore.findMemberId(sessionId)).isPresent();

        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        assertThat(setCookie).containsIgnoringCase("Max-Age=0");
        assertThat(sessionStore.findMemberId(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃 이후 세션 확인을 호출하면 401을 반환한다")
    void shouldReturnUnauthorizedWhenCheckingSessionAfterLogout() {
        saveCustomer("e2e_logout02", "p@ssw0rd", "01022223333");
        String cookie = loginAndGetSessionCookie("e2e_logout02", "p@ssw0rd");

        rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);
        var response = rest.exchange(SESSION_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("세션 쿠키가 없어도 로그아웃은 200을 반환한다")
    void shouldSucceedWithoutSessionCookie() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("존재하지 않는 세션이어도 로그아웃은 200을 반환한다")
    void shouldSucceedForUnknownSession() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST,
                withCookie("SESSION_ID=no-such-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
