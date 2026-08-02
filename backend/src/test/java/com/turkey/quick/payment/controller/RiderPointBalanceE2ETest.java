package com.turkey.quick.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
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

/**
 * 라이더 포인트 잔액 조회(#67) E2E. 이슈의 성공 조건과 예외 처리를 실제 HTTP 로 통과시킨다.
 *
 * <p>쿠키 없이 호출하면 401 이 나오는지를 한 케이스로 덮는 이유는 형식이 아니다 — 이 저장소는
 * Spring Security 를 쓰지 않아 인증이 {@code RiderWebMvcConfig.addPathPatterns} 등록에 달려 있고,
 * 등록을 빠뜨리면 이 API 가 인증 없이 열린 채로 배포된다({@code CLAUDE.md} 「확정된 결정」).
 * {@code RiderOperatingStatusE2ETest} 와 같은 이유로 같은 형태를 쓴다.
 *
 * <p>고객 세션 쿠키로 호출하는 케이스는 두지 않았다 — 역할 검증은 잔액 조회가 아니라
 * {@code RiderSessionInterceptor} 의 책임이고 이미 그쪽 테스트가 덮는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderPointBalanceE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String POINT_BALANCE_ENDPOINT = "/api/rider/points";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 회원 + 라이더 프로필 + 지갑을 만든다. 회원가입 API 를 타지 않고 직접 넣는 이유는 휴대전화 인증
     * 토큰까지 준비해야 해서 이 이슈와 무관한 사전 조건이 늘기 때문이다. 대신 저장하는 세 가지는
     * {@code RiderSignupService} 가 한 트랜잭션에서 만드는 것과 같다.
     *
     * @param balance 음수면 지갑 자체를 만들지 않는다(정합성 오류 재현용)
     */
    private void saveRider(String loginId, String phone, long balance) {
        tx().executeWithoutResult(t -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "김라이더", phone, MemberRole.RIDER));
            riderProfileRepository.save(RiderProfile.create(member));
            if (balance >= 0) {
                PointWallet wallet = PointWallet.create(member);
                if (balance > 0) {
                    wallet.credit(balance);
                }
                pointWalletRepository.save(wallet);
            }
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
    @DisplayName("로그인한 라이더가 조회하면 출금 가능 포인트 잔액을 받는다")
    void loggedInRiderSeesWithdrawableBalance() {
        saveRider("e2e_pt_bal", "01000000021", 48_000L);
        String cookie = loginAndGetSessionCookie("e2e_pt_bal");

        var response = rest.exchange(POINT_BALANCE_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // JSON 숫자는 기본적으로 Integer 로 역직렬화되므로 int 로 비교한다(RiderOperatingStatusE2ETest 와 동일).
        assertThat(response.getBody()).extracting("data.balance").isEqualTo(48_000);
        assertThat(response.getBody()).extracting("data.updatedAt").isNotNull();
    }

    @Test
    @DisplayName("정산 이력이 없는 라이더는 잔액 0 을 받는다")
    void freshRiderSeesZeroBalance() {
        saveRider("e2e_pt_zero", "01000000022", 0L);
        String cookie = loginAndGetSessionCookie("e2e_pt_zero");

        var response = rest.exchange(POINT_BALANCE_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data.balance").isEqualTo(0);
    }

    @Test
    @DisplayName("세션 쿠키 없이 조회하면 401 로 거부된다")
    void withoutCookieReturns401() {
        var response = rest.exchange(POINT_BALANCE_ENDPOINT, HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("지갑이 없는 라이더가 조회하면 500 으로 실패한다")
    void missingWalletReturns500() {
        // 회원가입이 지갑을 함께 만들므로 정상 경로에서는 생길 수 없는 상태다. 그래서 4xx 가 아니라
        // 5xx 이고, 여기서는 지갑만 빼고 넣어 그 판정을 실제 응답으로 확인한다.
        saveRider("e2e_pt_nowallet", "01000000023", -1L);
        String cookie = loginAndGetSessionCookie("e2e_pt_nowallet");

        var response = rest.exchange(POINT_BALANCE_ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
