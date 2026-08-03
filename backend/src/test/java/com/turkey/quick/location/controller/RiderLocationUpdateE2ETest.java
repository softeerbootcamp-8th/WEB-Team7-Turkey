package com.turkey.quick.location.controller;

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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 위치 갱신이 운행 상태에 따라 허용·거부되는지 실제 HTTP로 확인한다(#290·#291).
 *
 * <p>relay 대상(구독 중인 고객)이 있는지는 여기서 확인하지 않는다 — 이 컨트롤러가 하는 일은
 * 운행 상태 검증뿐이고, 구독자가 없어도 조용히 성공(200)한다는 것 자체가 {@code SseRelay}의
 * 정상 흐름이다(#290).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderLocationUpdateE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String LOCATION_ENDPOINT = "/api/rider/location";
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

    @Autowired
    private RiderGeoRepository riderGeoRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Member 저장과 RiderProfile(@MapsId) 저장을 한 트랜잭션으로 묶는다(RiderDeliveryRequestE2ETest와 같은 이유). */
    private Member saveRider(String loginId, String phoneNumber, OperatingStatus targetStatus) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode(PASSWORD), "홍라이더", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (targetStatus != OperatingStatus.UNAVAILABLE) {
                profile.goOnline();
            }
            if (targetStatus == OperatingStatus.BUSY) {
                profile.assign();
            }
            riderProfileRepository.save(profile);
            return member;
        });
    }

    private String login(String loginId) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", PASSWORD), ApiResponse.class);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        return setCookie.split(";")[0];
    }

    private Map<String, Object> locationRequest() {
        // 배송 식별자를 싣지 않는다 — 서버가 세션의 라이더로 수행 중 배송을 판정한다(#317).
        return Map.of(
                "latitude", "37.4979",
                "longitude", "127.0276",
                "measuredAt", Instant.now().toString());
    }

    private HttpEntity<Map<String, Object>> withCookie(Map<String, Object> body, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    @DisplayName("AVAILABLE 라이더가 위치를 보내면 200을 반환한다")
    void availableRiderCanSendLocation() {
        saveRider("e2e_location_available", "01011110001", OperatingStatus.AVAILABLE);
        String cookie = login("e2e_location_available");

        var response = rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST,
                withCookie(locationRequest(), cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
    }

    @Test
    @DisplayName("BUSY 라이더도 위치를 보낼 수 있다")
    void busyRiderCanSendLocation() {
        saveRider("e2e_location_busy", "01011110002", OperatingStatus.BUSY);
        String cookie = login("e2e_location_busy");

        var response = rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST,
                withCookie(locationRequest(), cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더가 위치를 보내면 409를 반환한다")
    void unavailableRiderCannotSendLocation() {
        saveRider("e2e_location_unavailable", "01011110003", OperatingStatus.UNAVAILABLE);
        String cookie = login("e2e_location_unavailable");

        var response = rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST,
                withCookie(locationRequest(), cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("운행 중이 아닙니다.");
    }

    @Test
    @DisplayName("AVAILABLE 라이더가 위치를 보내면 GEO 배차 후보로 등록된다(#83)")
    void availableRiderIsRegisteredAsGeoCandidate() {
        Member member = saveRider("e2e_location_geo_available", "01011110005", OperatingStatus.AVAILABLE);
        String cookie = login("e2e_location_geo_available");

        rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST, withCookie(locationRequest(), cookie), ApiResponse.class);

        assertThat(riderGeoRepository.findPosition(member.getId())).isPresent();
    }

    @Test
    @DisplayName("BUSY 라이더가 위치를 보내면 GEO 배차 후보에서 빠진다(#83)")
    void busyRiderIsRemovedFromGeoCandidates() {
        Member member = saveRider("e2e_location_geo_busy", "01011110006", OperatingStatus.BUSY);
        riderGeoRepository.registerOrUpdate(member.getId(), new BigDecimal("37.5000000"), new BigDecimal("127.0000000"));
        assertThat(riderGeoRepository.findPosition(member.getId())).isPresent();
        String cookie = login("e2e_location_geo_busy");

        rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST, withCookie(locationRequest(), cookie), ApiResponse.class);

        assertThat(riderGeoRepository.findPosition(member.getId())).isEmpty();
    }

    @Test
    @DisplayName("위치를 보내면 최신 위치가 Redis 에 남는다(#317)")
    void latestLocationIsStoredInRedis() {
        // 읽는 API 는 아직 없지만(#317 범위) 저장 자체가 스케일 아웃 준비의 절반이라, 실제로
        // 키가 생기고 TTL 이 걸리는지는 HTTP 경로에서 한 번 확인해 둔다.
        Member member = saveRider("e2e_location_latest", "01011110007", OperatingStatus.BUSY);
        String cookie = login("e2e_location_latest");

        rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST, withCookie(locationRequest(), cookie), ApiResponse.class);

        String key = "rider:location:%d".formatted(member.getId());
        assertThat(redisTemplate.opsForValue().get(key))
                .as("측정 시각이 맨 앞에 오는 형식")
                .startsWith("20");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isPositive();
    }

    @Test
    @DisplayName("세션 쿠키 없이 위치를 보내면 401을 반환한다")
    void rejectsWithoutSessionCookie() {
        var response = rest.exchange(LOCATION_ENDPOINT, HttpMethod.POST,
                withCookie(locationRequest(), null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
