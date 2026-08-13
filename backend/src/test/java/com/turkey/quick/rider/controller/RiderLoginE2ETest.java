package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬 Redis 없이 돌리기 위해 SessionStore를 인메모리 테스트 대체로 교체한다
 * (CustomerLoginE2ETest와 동일한 이유).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderLoginE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Member 저장과 RiderProfile(@MapsId) 저장을 하나의 트랜잭션으로 묶는다. 두 저장을 별도
     * 트랜잭션으로 나누면 두 번째 저장 시점에 member가 detached 상태라
     * PersistentObjectException이 난다 — 실제 RiderSignupService(#48)는 한 트랜잭션 안에서
     * 둘 다 처리하므로 이 문제가 없다.
     */
    private Member saveRider(String loginId, String rawPassword, String phoneNumber) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.RIDER));
            riderProfileRepository.save(RiderProfile.create(member));
            return member;
        });
    }

    @Test
    @DisplayName("올바른 로그인 정보면 200과 세션 쿠키 및 운행 상태를 반환한다")
    void shouldReturnSessionCookieAndOperatingStatusForValidCredentials() {
        Member member = saveRider("e2e_rider_login01", "p@ssw0rd", "01011112222");

        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "e2e_rider_login01", "password", "p@ssw0rd"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("loginId", "e2e_rider_login01")
                .containsEntry("operatingStatus", "UNAVAILABLE");

        var setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String sessionId = setCookie.get(0).split(";")[0].substring("SESSION_ID=".length());
        // 실제 Redis 에 세션이 이 형태로 저장됐는지 확인한다. 인메모리 대체를 쓸 때는 대체 구현의
        // 자료구조만 보는 셈이어서 저장 형태를 전혀 보장하지 못했다. 키 형식은 RedisSessionStore 의
        // 내부지만 "세션에 무엇이 담기는가"는 docs/03-erd.md 5절이 정한 계약이라 검증할 가치가 있다.
        assertThat(redisTemplate.opsForHash().get("session:" + sessionId, "memberId"))
                .isEqualTo(String.valueOf(member.getId()));
        assertThat(sessionStore.findMemberId(sessionId)).isPresent();
    }

    @Test
    @DisplayName("고객 계정으로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForCustomerAccount() {
        memberRepository.save(Member.create("e2e_customer_via_rider", PASSWORD_ENCODER.encode("p@ssw0rd"), "고객", "01033334444", MemberRole.CUSTOMER));

        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "e2e_customer_via_rider", "password", "p@ssw0rd"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("존재하지 않는 아이디로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForUnknownLoginId() {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", "no_such_rider", "password", "aaa"), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
