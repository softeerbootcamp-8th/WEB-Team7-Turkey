package com.turkey.quick.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
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

/**
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 테스트 대체로 교체한다
 * (CustomerLoginE2ETest와 동일한 이유).
 */
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
    private InMemorySessionStore sessionStore;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        InMemorySessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

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
    void 로그인_후_로그아웃하면_200과_만료_쿠키를_반환하고_세션이_삭제된다() {
        saveCustomer("e2e_logout01", "p@ssw0rd", "01011112222");
        String cookie = loginAndGetSessionCookie("e2e_logout01", "p@ssw0rd");
        String sessionId = cookie.substring("SESSION_ID=".length());
        assertThat(sessionStore.get(sessionId)).isNotNull();

        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        assertThat(setCookie).containsIgnoringCase("Max-Age=0");
        assertThat(sessionStore.get(sessionId)).isNull();
    }

    @Test
    void 로그아웃_이후_세션_확인을_호출하면_401을_반환한다() {
        saveCustomer("e2e_logout02", "p@ssw0rd", "01022223333");
        String cookie = loginAndGetSessionCookie("e2e_logout02", "p@ssw0rd");

        rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(cookie), ApiResponse.class);
        var response = rest.exchange(SESSION_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 세션_쿠키가_없어도_로그아웃은_200을_반환한다() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 존재하지_않는_세션이어도_로그아웃은_200을_반환한다() {
        var response = rest.exchange(LOGOUT_ENDPOINT, HttpMethod.POST,
                withCookie("SESSION_ID=no-such-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
