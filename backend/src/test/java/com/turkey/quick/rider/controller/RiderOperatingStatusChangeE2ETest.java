package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 상태 변경(#54) E2E. 이슈의 성공/예외 흐름을 실제 HTTP 로 통과시킨다.
 *
 * <p>PATCH 는 {@code TestRestTemplate} 기본 팩토리(JDK {@code HttpURLConnection})가 지원하지 않으므로
 * JDK 21 {@code java.net.http.HttpClient} 로 보낸다({@code SseTestClient} 와 같은 이유 — 새 의존성 없음).
 * 로그인·조회(GET)는 {@code TestRestTemplate} 를 그대로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderOperatingStatusChangeE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String OPERATING_STATUS_ENDPOINT = "/api/rider/operating-status";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private RiderGeoRepository riderGeoRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Long saveRider(String loginId, String phone, OperatingStatus status) {
        return new TransactionTemplate(transactionManager).execute(t -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "홍길동", phone, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
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

    /** JDK HttpClient 로 PATCH 를 보내고 상태 코드를 돌려준다. cookie 가 null 이면 실지 않는다. */
    private int patchAction(String cookie, String action) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + OPERATING_STATUS_ENDPOINT))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"action\":\"" + action + "\"}"));
        if (cookie != null) {
            builder.header(HttpHeaders.COOKIE, cookie);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private Object getOperatingStatus(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        var response = rest.exchange(OPERATING_STATUS_ENDPOINT, HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);
        return ((Map<?, ?>) response.getBody().data()).get("operatingStatus");
    }

    @Test
    @DisplayName("콜 받기(GO_ONLINE) 하면 200, 재조회 시에도 AVAILABLE 로 유지된다")
    void goOnlineSucceedsAndPersists() throws Exception {
        saveRider("e2e_go_online", "01000000201", OperatingStatus.UNAVAILABLE);
        String cookie = loginAndGetSessionCookie("e2e_go_online");

        assertThat(patchAction(cookie, "GO_ONLINE")).isEqualTo(HttpStatus.OK.value());
        assertThat(getOperatingStatus(cookie)).isEqualTo("AVAILABLE"); // 재조회 유지
    }

    @Test
    @DisplayName("운행 종료(GO_OFFLINE) 하면 200·UNAVAILABLE 이 되고 최신 위치가 배차 후보에서 제외된다")
    void goOfflineSucceedsAndRemovesFromCandidates() throws Exception {
        Long riderId = saveRider("e2e_go_offline", "01000000202", OperatingStatus.AVAILABLE);
        riderGeoRepository.registerOrUpdate(riderId, new BigDecimal("37.5000000"), new BigDecimal("127.0000000"));
        String cookie = loginAndGetSessionCookie("e2e_go_offline");

        assertThat(patchAction(cookie, "GO_OFFLINE")).isEqualTo(HttpStatus.OK.value());
        assertThat(getOperatingStatus(cookie)).isEqualTo("UNAVAILABLE");
        assertThat(riderGeoRepository.findPosition(riderId)).isEmpty(); // 즉시 배차 후보 제외
    }

    @Test
    @DisplayName("BUSY 라이더가 직접 변경을 요청하면 409 로 거부된다")
    void busyRiderChangeIsRejected() throws Exception {
        saveRider("e2e_busy_change", "01000000203", OperatingStatus.BUSY);
        String cookie = loginAndGetSessionCookie("e2e_busy_change");

        assertThat(patchAction(cookie, "GO_OFFLINE")).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    @DisplayName("이미 같은 상태로 요청하면 오류 없이 200 을 반환한다(멱등)")
    void sameStatusIsIdempotent() throws Exception {
        saveRider("e2e_idem_change", "01000000204", OperatingStatus.AVAILABLE);
        String cookie = loginAndGetSessionCookie("e2e_idem_change");

        assertThat(patchAction(cookie, "GO_ONLINE")).isEqualTo(HttpStatus.OK.value()); // 이미 AVAILABLE
        assertThat(getOperatingStatus(cookie)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("세션 쿠키 없이 변경을 요청하면 401 로 거부된다")
    void withoutCookieReturns401() throws Exception {
        assertThat(patchAction(null, "GO_ONLINE")).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
