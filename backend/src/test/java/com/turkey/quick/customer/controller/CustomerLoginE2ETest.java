package com.turkey.quick.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 테스트 대체로 교체한다
 * (PhoneVerificationE2ETest와 동일한 이유).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerLoginE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/customer/login";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Member saveCustomer(String loginId, String rawPassword, String phoneNumber) {
        return memberRepository.save(
                Member.create(loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.CUSTOMER));
    }

    @Test
    void 올바른_로그인_정보면_200과_세션_쿠키를_반환한다() {
        Member member = saveCustomer("e2e_login01", "p@ssw0rd", "01011112222");

        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "e2e_login01", "password", "p@ssw0rd"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("loginId", "e2e_login01");

        var setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String cookie = setCookie.get(0);
        assertThat(cookie).contains("SESSION_ID=");
        assertThat(cookie).containsIgnoringCase("HttpOnly");
        assertThat(cookie).containsIgnoringCase("SameSite=Lax");

        String sessionId = cookie.split(";")[0].substring("SESSION_ID=".length());
        // 실제 Redis 에 세션이 이 형태로 저장됐는지 확인한다. 인메모리 대체를 쓸 때는 대체 구현의
        // 자료구조만 보는 셈이어서 저장 형태를 전혀 보장하지 못했다. 키 형식은 RedisSessionStore 의
        // 내부지만 "세션에 무엇이 담기는가"는 docs/03-erd.md 5절이 정한 계약이라 검증할 가치가 있다.
        assertThat(redisTemplate.opsForHash().get("session:" + sessionId, "memberId"))
                .isEqualTo(String.valueOf(member.getId()));
        assertThat(sessionStore.findMemberId(sessionId)).isPresent();
    }

    @Test
    void 존재하지_않는_아이디로_로그인하면_401을_반환한다() {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "no_such_login_id", "password", "aaa"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 비밀번호가_틀리면_401을_반환한다() {
        saveCustomer("e2e_login02", "p@ssw0rd", "01022223333");

        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "e2e_login02", "password", "wrong"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_계정으로_로그인하면_401을_반환한다() {
        memberRepository.save(Member.create("e2e_rider01", PASSWORD_ENCODER.encode("p@ssw0rd"), "라이더", "01033334444", MemberRole.RIDER));

        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "e2e_rider01", "password", "p@ssw0rd"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 비밀번호가_없으면_400을_반환한다() {
        var response = rest.postForEntity(LOGIN_ENDPOINT, Map.of("loginId", "someone"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
