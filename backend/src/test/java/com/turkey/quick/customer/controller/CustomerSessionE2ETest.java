package com.turkey.quick.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 테스트 대체로 교체한다
 * (CustomerLoginE2ETest와 동일한 이유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerSessionE2ETest {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
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
    void 유효한_세션_쿠키면_200과_고객_정보를_반환한다() {
        saveCustomer("e2e_session01", "p@ssw0rd", "01011112222");
        String cookie = loginAndGetSessionCookie("e2e_session01", "p@ssw0rd");

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("loginId", "e2e_session01");
    }

    @Test
    void 세션_쿠키가_없으면_401을_반환한다() {
        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 존재하지_않는_세션이면_401을_반환한다() {
        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie("SESSION_ID=no-such-session"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 로그인_이후_탈퇴한_계정이면_401을_반환한다() {
        Member member = saveCustomer("e2e_session02", "p@ssw0rd", "01022223333");
        String cookie = loginAndGetSessionCookie("e2e_session02", "p@ssw0rd");

        member.withdraw();
        memberRepository.save(member);

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_세션으로_고객_세션_확인을_시도하면_401을_반환한다() {
        Member rider = memberRepository.save(
                Member.create("e2e_rider_session", PASSWORD_ENCODER.encode("p@ssw0rd"), "라이더", "01033334444", MemberRole.RIDER));
        String sessionId = "rider-session-id";
        sessionStore.create(sessionId, rider.getId(), "RIDER", java.time.Duration.ofHours(2));

        var response = rest.exchange(SESSION_ENDPOINT, org.springframework.http.HttpMethod.GET,
                withCookie("SESSION_ID=" + sessionId), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
