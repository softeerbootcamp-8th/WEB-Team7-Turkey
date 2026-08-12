package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 기록 목록 조회(#70) E2E. 이슈의 성공 조건("본인 기록만 최신순")과 예외("기록 없으면 빈 목록",
 * "미로그인 요청은 오류")를 실제 HTTP 로 통과시킨다.
 *
 * <p>쿠키 없이 호출하면 401 이 나오는지를 덮는 이유는 {@code RiderPointBalanceE2ETest} 와 같다 —
 * 인증이 {@code RiderWebMvcConfig.addPathPatterns} 등록에 달려 있어, 등록을 빠뜨리면 이 API 가
 * 인증 없이 열린 채로 배포된다. 정렬·페이지 경계는 통합 테스트가 촘촘히 덮으므로 E2E 는 계약
 * (상태 코드·응답 형태)만 확인한다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryHistoryE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String HISTORY_ENDPOINT = "/api/rider/history";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TrackingFixture trackingFixture;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("로그인한 라이더가 조회하면 본인 완료 배송이 목록으로 온다")
    void loggedInRiderSeesOwnHistory() {
        TrackingFixture.Scenario scenario = trackingFixture.deliveryWithStatus(OrderStatus.COMPLETED);
        String cookie = loginAndGetSessionCookie(scenario.riderLoginId());

        var response = rest.exchange(HISTORY_ENDPOINT + "?page=0&size=10", HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // JSON 숫자는 기본적으로 Integer 로 역직렬화된다(다른 라이더 E2E 와 동일).
        assertThat(response.getBody()).extracting("data.totalElements").isEqualTo(1);
        assertThat(response.getBody()).extracting("data.items").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(Object.class)).hasSize(1);
    }

    @Test
    @DisplayName("완료 기록이 없는 라이더는 빈 목록을 받는다")
    void riderWithoutHistorySeesEmptyList() {
        String loginId = saveBareRider("e2e_hist_empty", "01000000031");
        String cookie = loginAndGetSessionCookie(loginId);

        var response = rest.exchange(HISTORY_ENDPOINT + "?page=0&size=10", HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data.totalElements").isEqualTo(0);
        assertThat(response.getBody()).extracting("data.items").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(Object.class)).isEmpty();
    }

    @Test
    @DisplayName("세션 쿠키 없이 조회하면 401 로 거부된다")
    void withoutCookieReturns401() {
        var response = rest.exchange(HISTORY_ENDPOINT, HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 완료 배송이 없는 순수 라이더를 만든다(TrackingFixture 는 항상 배송을 만들어 빈 목록을 못 만든다). */
    private String saveBareRider(String loginId, String phone) {
        new TransactionTemplate(transactionManager).executeWithoutResult(t -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode(TrackingFixture.PASSWORD), "김라이더", phone, MemberRole.RIDER));
            riderProfileRepository.save(RiderProfile.create(member));
        });
        return loginId;
    }

    private String loginAndGetSessionCookie(String loginId) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        return cookies.get(0).split(";")[0];
    }

    private HttpEntity<Void> withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(headers);
    }
}
