package com.turkey.quick.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.service.InMemoryRiderLocationStore;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 로컬 Redis 없이 돌리기 위해 SessionStore·RiderLocationStore 를 인메모리 테스트 대체로
 * 교체한다(RiderSessionE2ETest 와 동일한 이유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("POST /api/rider/location")
class RiderLocationE2ETest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/api/rider/location";
    private static final String RIDER_LOGIN = "/api/rider/login";
    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String PASSWORD = "p@ssw0rd";
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

        @Bean
        @Primary
        InMemoryRiderLocationStore riderLocationStore() {
            return new InMemoryRiderLocationStore();
        }
    }

    /**
     * 운행 상태를 인자로 받는 이유: RiderProfile.create 는 UNAVAILABLE 로 만들어지고, 운행 상태를
     * 바꾸는 API 는 아직 없다. 200 경로를 검증하려면 여기서 직접 AVAILABLE 로 올려야 한다.
     */
    private void saveRider(String loginId, String phoneNumber, boolean online) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Member member = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(PASSWORD), "홍길동", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (online) {
                profile.goOnline();
            }
            riderProfileRepository.save(profile);
        });
    }

    private void saveCustomer(String loginId, String phoneNumber) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                memberRepository.save(Member.create(
                        loginId, PASSWORD_ENCODER.encode(PASSWORD), "김고객", phoneNumber, MemberRole.CUSTOMER)));
    }

    private String loginAndGetSessionCookie(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split(";")[0];
    }

    /** 좌표·정확도는 기본값을 쓰고, 케이스마다 바꿀 필드만 덮어쓴다. */
    private Map<String, Object> body(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "latitude", 37.4979,
                "longitude", 127.0276,
                "measuredAt", Instant.now().toString(),
                "accuracyMeters", 12.5));
        body.putAll(overrides);
        return body;
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private org.springframework.http.ResponseEntity<ApiResponse> post(String cookie, Map<String, Object> body) {
        return rest.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(body, headers(cookie)), ApiResponse.class);
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("세션 쿠키가 없으면 401이다")
        void rejectsRequestWithoutSessionCookie() {
            // 이 케이스가 RiderWebMvcConfig 의 addPathPatterns 등록 누락을 잡는 회귀 테스트다.
            // 등록을 빠뜨리면 인터셉터가 안 걸려 이 요청이 200 이 되고, 그러면 이 API 는 인증
            // 없이 열린 채로 배포된다(CLAUDE.md 「확정된 결정」).
            assertThat(post(null, body(Map.of())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("고객 세션으로는 401이다")
        void rejectsCustomerSession() {
            saveCustomer("e2e_loc_customer", "01033330001");
            String cookie = loginAndGetSessionCookie(CUSTOMER_LOGIN, "e2e_loc_customer");

            assertThat(post(cookie, body(Map.of())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("없는 세션이면 401과 만료 쿠키를 반환한다")
        void expiresCookieOnUnknownSession() {
            var response = post("SESSION_ID=no-such-session", body(Map.of()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                    .containsIgnoringCase("Max-Age=0");
        }
    }

    @Nested
    @DisplayName("운행 상태")
    class OperatingStatus {

        @Test
        @DisplayName("운행 중인 라이더의 위치는 200으로 갱신된다")
        void updatesLocationWhenOperating() {
            saveRider("e2e_loc_online", "01011110001", true);
            String cookie = loginAndGetSessionCookie(RIDER_LOGIN, "e2e_loc_online");

            var response = post(cookie, body(Map.of()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).extracting("data").asInstanceOf(MAP)
                    .containsEntry("applied", true)
                    .containsEntry("published", false)
                    .containsEntry("reason", "ACCEPTED");
        }

        @Test
        @DisplayName("운행 중이 아니면 409다")
        void rejectsWhenNotOperating() {
            // 가입 직후 라이더는 UNAVAILABLE 이다.
            saveRider("e2e_loc_offline", "01011110002", false);
            String cookie = loginAndGetSessionCookie(RIDER_LOGIN, "e2e_loc_offline");

            var response = post(cookie, body(Map.of()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).extracting("success").isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class RequestValidation {

        private String onlineRiderCookie(String loginId, String phoneNumber) {
            saveRider(loginId, phoneNumber, true);
            return loginAndGetSessionCookie(RIDER_LOGIN, loginId);
        }

        @Test
        @DisplayName("좌표가 범위를 벗어나면 400이다")
        void rejectsCoordinateOutOfRange() {
            String cookie = onlineRiderCookie("e2e_loc_range", "01011110003");

            var response = post(cookie, body(Map.of("latitude", 91)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("측정 시각이 미래면 400이다")
        void rejectsFutureMeasuredAt() {
            String cookie = onlineRiderCookie("e2e_loc_future", "01011110004");

            var response = post(cookie, body(Map.of("measuredAt", Instant.now().plusSeconds(600).toString())));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            // 정책이 던진 예외를 감싸지 않아야 한국어 메시지가 그대로 나온다.
            assertThat(response.getBody().message()).contains("측정 시각");
        }

        @Test
        @DisplayName("정확도가 기준에 못 미치면 400이 아니라 200으로 버린다")
        void discardsLowAccuracyWithoutError() {
            // 실내·지하 측위에서 정상적으로 발생하는 조건이라 오류로 응답하지 않는다(#234 결정).
            String cookie = onlineRiderCookie("e2e_loc_accuracy", "01011110005");

            var response = post(cookie, body(Map.of("accuracyMeters", 500)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).extracting("data").asInstanceOf(MAP)
                    .containsEntry("applied", false)
                    .containsEntry("reason", "LOW_ACCURACY");
        }

        @Test
        @DisplayName("같은 요청을 다시 보내면 최신 위치를 덮지 않는다")
        void discardsResendWithSameMeasuredAt() {
            // 프론트가 실패로 오해해 재전송하는 경우다. 400 이 아니라 200 + NON_MONOTONIC 이다.
            String cookie = onlineRiderCookie("e2e_loc_resend", "01011110006");
            Map<String, Object> sameBody = body(Map.of());

            assertThat(post(cookie, sameBody).getStatusCode()).isEqualTo(HttpStatus.OK);
            var second = post(cookie, sameBody);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getBody()).extracting("data").asInstanceOf(MAP)
                    .containsEntry("applied", false)
                    .containsEntry("reason", "NON_MONOTONIC");
        }
    }
}
