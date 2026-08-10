package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.SecondaryInstance;
import com.turkey.quick.support.SseTestClient;
import com.turkey.quick.support.TrackingFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * <b>이 기능의 핵심을 증명하는 유일한 자동 검증이다</b>(#317).
 *
 * <p>인스턴스 A 에 고객 SSE 를 연결하고, <b>인스턴스 B</b> 에 라이더 위치를 POST 해서 A 의 스트림에
 * 이벤트가 도달하는지 본다. B 는 그 배송의 {@code SseEmitter} 를 갖고 있지 않으므로
 * <b>Redis Pub/Sub 팬아웃 말고는 어떤 구현으로도 통과할 수 없다.</b> 나머지 모든 테스트는 1대에서
 * 돌기 때문에 팬아웃을 빼고 직접 전달해도 통과한다.
 *
 * <p>역방향(A 에 POST, A 에서 수신)만 있으면 의미가 없다. 교차 케이스가 전부다.
 *
 * <p>두 번째 인스턴스를 {@code @BeforeAll} 에서 띄우는 이유: {@code IntegrationTestSupport} 의
 * {@code @BeforeEach} 가 MySQL·Redis 를 비우므로 <b>픽스처는 그 뒤에</b> 만들어야 한다. 상속상
 * 부모의 {@code @BeforeEach} 가 먼저 돌아 자연히 만족된다. B 는 상태를 캐싱하지 않으므로 미리
 * 띄워 둬도 무방하다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("Pub/Sub 팬아웃 (2인스턴스)")
class TrackingFanoutMultiInstanceE2ETest extends IntegrationTestSupport {

    private static final String CUSTOMER_LOGIN = "/api/customer/login";
    private static final String RIDER_LOGIN = "/api/rider/login";
    private static final String RIDER_LOCATION = "/api/rider/location";
    private static final String RIDER_DELIVERY_TRANSITION = "/api/rider/deliveries/%d/transition";
    private static final String RIDER_ACCEPT_REQUEST = "/api/rider/requests/%d/accept";
    private static final Duration AWAIT = Duration.ofSeconds(10);

    private static SecondaryInstance secondary;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private SseRegistry registryA;

    @BeforeAll
    static void startSecondInstance() {
        secondary = SecondaryInstance.start();
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondary != null) {
            secondary.close();
        }
    }

    private String streamUrlOnA(Long deliveryId) {
        return "http://localhost:%d/api/customer/deliveries/%d/tracking/stream".formatted(port, deliveryId);
    }

    /**
     * 로그인은 인스턴스 A 에서 하고 그 쿠키를 B 에도 쓴다. 세션이 Redis 에 있으므로 그대로
     * 통해야 한다 — <b>스티키 세션이 필요 없다는 것</b>이 여기서 부수적으로 증명된다.
     */
    private String loginOnA(String endpoint, String loginId) {
        var response = rest.postForEntity(endpoint,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        assertThat(response.getStatusCode()).as("인스턴스 A 로그인").isEqualTo(HttpStatus.OK);
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
    }

    private HttpHeaders withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    /**
     * <b>배송 식별자를 싣지 않는다.</b> 서버가 세션의 라이더로 "수행 중 배송"을 DB 에서 풀어
     * 채널을 정한다 — 그래서 이 테스트는 팬아웃뿐 아니라 <b>발행 대상 판정까지</b> 검증한다.
     */
    private Map<String, Object> locationBody(String latitude) {
        Map<String, Object> body = new HashMap<>();
        body.put("latitude", latitude);
        body.put("longitude", "127.0276");
        body.put("measuredAt", Instant.now().toString());
        body.put("accuracyMeters", "12.5");
        return body;
    }

    private void postLocation(String url, String latitude, String riderCookie) {
        var posted = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(locationBody(latitude), withCookie(riderCookie)),
                ApiResponse.class);
        assertThat(posted.getStatusCode()).as("위치 갱신 %s", url).isEqualTo(HttpStatus.OK);
    }

    private void postTransition(String url, String action, String riderCookie) {
        var posted = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(Map.of("action", action), withCookie(riderCookie)),
                ApiResponse.class);
        assertThat(posted.getStatusCode()).as("단계 전이 %s", url).isEqualTo(HttpStatus.OK);
    }

    private void postAccept(String url, String riderCookie) {
        var posted = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(Map.of(), withCookie(riderCookie)),
                ApiResponse.class);
        assertThat(posted.getStatusCode()).as("배차 수락 %s", url).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("B 에 올린 위치가 A 에 연결된 고객 스트림으로 전달된다")
    void deliversAcrossInstances() {
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).isEqualTo(200);

            // 인스턴스 A 만 이 배송의 emitter 를 갖고 있다. 이 전제가 깨지면 아래 단언이
            // 팬아웃을 증명하지 못한다.
            assertThat(registryA.connectionOf(scenario.deliveryId()))
                    .as("A 가 연결을 들고 있다").hasSize(1);
            assertThat(secondary.bean(SseRegistry.class).connectionOf(scenario.deliveryId()))
                    .as("B 는 연결을 들고 있지 않다 — 레지스트리가 static 이면 여기서 깨진다")
                    .isEmpty();
            assertThat(secondary.bean(SseRegistry.class))
                    .as("두 인스턴스의 레지스트리는 서로 다른 객체여야 한다")
                    .isNotSameAs(registryA);

            // 라이더 위치를 인스턴스 B 로 보낸다. B 는 emitter 가 없다 — Pub/Sub 팬아웃뿐이다.
            postLocation(secondary.url(RIDER_LOCATION), "37.4979", riderCookie);

            assertThat(client.awaitData(AWAIT)).contains("\"latitude\":37.4979");
        }
    }

    @Test
    @DisplayName("A 에 올린 위치도 A 의 스트림으로 전달된다")
    void deliversWithinSameInstance() {
        // 같은 인스턴스 경로도 팬아웃을 지난다(자기 자신의 패턴 구독으로 되돌아온다). 이 케이스만
        // 있으면 "직접 전달" 구현도 통과하므로 위 교차 케이스와 함께여야 의미가 있다.
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            postLocation("http://localhost:%d%s".formatted(port, RIDER_LOCATION), "37.5665", riderCookie);

            assertThat(client.awaitData(AWAIT)).contains("\"latitude\":37.5665");
        }
    }

    @Test
    @DisplayName("전달된 프레임은 프론트 파서가 요구하는 형식이다")
    void deliveredFrameMatchesFrontendContract() {
        // 프론트 parseLocationPing 은 latitude·longitude 가 숫자, measuredAt 이 문자열이 아니면
        // 프레임을 통째로 버린다(null 반환). 그러면 지도 마커가 조용히 멈추므로 계약을 고정한다.
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            postLocation(secondary.url(RIDER_LOCATION), "37.4979", riderCookie);

            assertThat(client.awaitData(AWAIT))
                    .contains("\"latitude\":37.4979")
                    .contains("\"longitude\":127.0276")
                    .contains("\"accuracyMeters\":12.5")
                    // 따옴표까지 포함해 문자열임을 단언한다 — 숫자 타임스탬프로 바뀌면 여기서 깨진다.
                    .containsPattern("\"measuredAt\":\"[0-9T:.Z-]+\"");
        }
    }

    @Test
    @DisplayName("다른 배송을 구독한 고객에게는 전달되지 않는다")
    void doesNotLeakToOtherDeliverySubscribers() {
        // 모든 인스턴스가 패턴으로 모든 이벤트를 받으므로, 배송별 필터가 실제로 동작하는지
        // 확인해야 한다. 여기가 새면 남의 배송 좌표가 다른 고객 화면에 뜬다.
        var watched = fixture.assignedDelivery();
        var other = fixture.assignedDelivery();
        String watcherCookie = loginOnA(CUSTOMER_LOGIN, watched.customerLoginId());
        String otherRiderCookie = loginOnA(RIDER_LOGIN, other.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(watched.deliveryId()), watcherCookie)) {
            postLocation(secondary.url(RIDER_LOCATION), "38.1111", otherRiderCookie);

            // 자기 배송으로 한 번 더 보내 "그 사이에 아무것도 오지 않았다"를 확인할 기준점을 만든다.
            postLocation(secondary.url(RIDER_LOCATION), "37.4979",
                    loginOnA(RIDER_LOGIN, watched.riderLoginId()));

            // 자기 배송의 좌표가 먼저 도착해야 한다. 남의 좌표가 섞였다면 이 프레임이 38.1111 이다.
            assertThat(client.awaitData(AWAIT)).contains("\"latitude\":37.4979");
            assertThat(client.receivedLines()).noneMatch(line -> line.contains("38.1111"));
        }
    }

    @Test
    @DisplayName("B 에서 일어난 배송 상태 전이가 A 에 연결된 고객 스트림으로 전달된다(#398)")
    void deliversStatusChangeAcrossInstances() {
        // 위치와 같은 채널·같은 팬아웃 경로를 타는지 확인한다. 배차(ASSIGNED)는 픽스처가 도메인
        // 메서드로 직접 만들어서(위 클래스 Javadoc) 검증 대상이 아니고, 그 다음 실제 HTTP 전이
        // (ASSIGNED→MOVING_TO_PICKUP)부터가 이 테스트의 대상이다.
        var scenario = fixture.assignedDelivery();
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).isEqualTo(200);

            postTransition(secondary.url(RIDER_DELIVERY_TRANSITION.formatted(scenario.deliveryId())),
                    "START_MOVING_TO_PICKUP", riderCookie);

            assertThat(client.awaitData(AWAIT))
                    .contains("\"type\":\"status\"")
                    .contains("\"status\":\"MOVING_TO_PICKUP\"");
        }
    }

    @Test
    @DisplayName("B 에서 완료 처리하면 A 가 들고 있던 연결이 닫힌다 — 이 기능의 핵심 (#450)")
    void closesConnectionOnOtherInstanceWhenCompleted() {
        // 완료 처리를 한 인스턴스(B)와 고객 emitter 를 들고 있는 인스턴스(A)가 다르다는 것이
        // 이 설계의 출발점이다. B 는 A 의 emitter 를 직접 부를 수 없으므로 종료도 데이터와
        // 똑같이 Pub/Sub 을 거쳐야 한다 — 그 사실을 증명하는 유일한 테스트다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                pointWalletRepository.save(
                        PointWallet.create(memberRepository.findById(scenario.riderId()).orElseThrow())));
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).isEqualTo(200);
            assertThat(registryA.connectionOf(scenario.deliveryId()))
                    .as("A 가 연결을 들고 있다 — 이 전제가 깨지면 아래 단언이 아무것도 증명하지 못한다")
                    .hasSize(1);
            assertThat(secondary.bean(SseRegistry.class).connectionOf(scenario.deliveryId()))
                    .as("B 는 그 연결을 갖고 있지 않다").isEmpty();

            completeOnSecondary(scenario.deliveryId(), riderCookie);

            awaitConnectionClosed(scenario.deliveryId());
        }
    }

    /** 종료는 Pub/Sub 왕복 뒤 다른 스레드에서 일어난다. 이 저장소는 Awaitility 를 쓰지 않아 직접 폴링한다. */
    private void awaitConnectionClosed(Long deliveryId) {
        long deadline = System.currentTimeMillis() + AWAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (registryA.connectionOf(deliveryId).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("종료 대기가 중단됐습니다.", e);
            }
        }
        assertThat(registryA.connectionOf(deliveryId))
                .as("완료 후 A 의 연결이 정리돼야 한다").isEmpty();
    }

    private void completeOnSecondary(Long deliveryId, String riderCookie) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("proofType", "PHOTO");
        body.add("proofValue", "proof/close-test.jpg");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, riderCookie);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        var completed = rest.exchange(
                secondary.url("/api/rider/deliveries/%d/complete".formatted(deliveryId)),
                HttpMethod.POST, new HttpEntity<>(body, headers), ApiResponse.class);
        assertThat(completed.getStatusCode()).as("배송 완료 처리 body=%s", completed.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("B 에서 배송 완료 처리되면 A 에 연결된 고객 스트림으로 COMPLETED가 전달된다(#398)")
    void deliversCompletedEventAcrossInstances() {
        // complete()는 transition()과 다른 메서드다 — 정산·포인트 적립까지 한 트랜잭션에서
        // 처리하므로 발행 호출부가 독립적이다. transition() 케이스가 통과한다고 이 경로도
        // 통과한다고 볼 수 없다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.DELIVERING);
        // TrackingFixture는 위치·추적 테스트용이라 포인트 지갑을 만들지 않는다 — complete()의
        // 정산 적립이 지갑을 요구하므로 여기서 직접 채워 준다(회원가입 때는 항상 함께 생긴다).
        // 지갑·회원을 같은 트랜잭션(같은 영속성 컨텍스트)에서 조회·저장해야 한다 — 서로 다른
        // 리포지토리 호출로 나누면 @MapsId 연관의 Member 가 준영속 상태로 넘어가
        // "uninitialized proxy passed to persist()" 로 깨진다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                pointWalletRepository.save(
                        PointWallet.create(memberRepository.findById(scenario.riderId()).orElseThrow())));
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).isEqualTo(200);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("proofType", "PHOTO");
            body.add("proofValue", "proof/fanout-test.jpg");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, riderCookie);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            var completed = rest.exchange(
                    secondary.url("/api/rider/deliveries/%d/complete".formatted(scenario.deliveryId())),
                    HttpMethod.POST, new HttpEntity<>(body, headers), ApiResponse.class);
            assertThat(completed.getStatusCode()).as("배송 완료 처리 body=%s", completed.getBody())
                    .isEqualTo(HttpStatus.OK);

            assertThat(client.awaitData(AWAIT))
                    .contains("\"type\":\"status\"")
                    .contains("\"status\":\"COMPLETED\"");

            // 실제 버그(사람 확인, 2026-08-06): 발행이 정산·포인트 적립보다 먼저 일어나면, 이
            // 이벤트를 받고 고객이 곧바로 재조회해도 원본 트랜잭션이 아직 커밋 전이라 옛 상태
            // (DELIVERING)를 읽는다 — 화면이 새로고침 전까지 안 바뀌는 것처럼 보인다. 이벤트
            // 수신 직후의 재조회가 이미 COMPLETED를 보는지까지 확인해야 이 커밋 시점 경쟁을 잡는다.
            var detail = rest.exchange(streamUrlOnA(scenario.deliveryId())
                            .replace("/tracking/stream", ""),
                    HttpMethod.GET, new HttpEntity<>(withCookie(customerCookie)), JsonNode.class);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody().get("data").get("status").asText())
                    .as("이벤트 수신 직후 재조회한 상태 — 커밋 전이면 아직 DELIVERING으로 보인다")
                    .isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("WAITING일 때 미리 연결해 두면 배차 확정(ASSIGNED)을 그 연결로 받는다(#398+#401)")
    void receivesAssignedEventOnConnectionOpenedWhileWaiting() {
        // 이게 이번 세 서브 이슈(#398/#399/#401)를 합쳐야 성립하는 핵심 시나리오다: 라이더가
        // 수락하기 *전*(WAITING)부터 고객이 이미 연결해 둔 상태에서, 그 연결로 ASSIGNED 전이가
        // 도착해야 한다. 연결이 배차 *이후*에나 열린다면(#401 이전) 이 전이 자체를 절대 못 받는다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).as("WAITING도 이제 200이어야 한다(#401)").isEqualTo(200);

            postAccept(secondary.url(RIDER_ACCEPT_REQUEST.formatted(scenario.deliveryId())), riderCookie);

            assertThat(client.awaitData(AWAIT))
                    .contains("\"type\":\"status\"")
                    .contains("\"status\":\"ASSIGNED\"");
        }
    }

    @Test
    @DisplayName("WAITING부터 COMPLETED까지, 하나의 연결로 모든 상태 전이를 순서대로 받는다(사용자 보고 버그 재현)")
    void receivesEveryStatusTransitionOnOneLongLivedConnection() {
        // 지금까지의 케이스는 전이 하나씩을 갓 연 연결로 확인했다. 실제 사용자 흐름은 연결 하나가
        // WAITING부터 COMPLETED까지 계속 열려 있다 — "완료 시에만 화면이 안 바뀐다"는 버그 신고를
        // 재현하려면, 그 앞의 네 번의 전이(ASSIGNED/MOVING_TO_PICKUP/PICKED_UP/DELIVERING)를
        // 이미 받은 뒤에도 다섯 번째(COMPLETED)가 같은 연결로 여전히 도착하는지 확인해야 한다.
        var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                pointWalletRepository.save(
                        PointWallet.create(memberRepository.findById(scenario.riderId()).orElseThrow())));
        String customerCookie = loginOnA(CUSTOMER_LOGIN, scenario.customerLoginId());
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        try (var client = SseTestClient.get(streamUrlOnA(scenario.deliveryId()), customerCookie)) {
            assertThat(client.statusCode()).isEqualTo(200);

            postAccept(secondary.url(RIDER_ACCEPT_REQUEST.formatted(scenario.deliveryId())), riderCookie);
            assertThat(client.awaitData(AWAIT)).as("1: ASSIGNED").contains("\"status\":\"ASSIGNED\"");

            String transitionUrl = secondary.url(RIDER_DELIVERY_TRANSITION.formatted(scenario.deliveryId()));
            postTransition(transitionUrl, "START_MOVING_TO_PICKUP", riderCookie);
            assertThat(client.awaitData(AWAIT)).as("2: MOVING_TO_PICKUP").contains("\"status\":\"MOVING_TO_PICKUP\"");

            postTransition(transitionUrl, "PICK_UP", riderCookie);
            assertThat(client.awaitData(AWAIT)).as("3: PICKED_UP").contains("\"status\":\"PICKED_UP\"");

            postTransition(transitionUrl, "START_DELIVERING", riderCookie);
            assertThat(client.awaitData(AWAIT)).as("4: DELIVERING").contains("\"status\":\"DELIVERING\"");

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("proofType", "PHOTO");
            body.add("proofValue", "proof/full-journey-test.jpg");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, riderCookie);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            var completed = rest.exchange(
                    secondary.url("/api/rider/deliveries/%d/complete".formatted(scenario.deliveryId())),
                    HttpMethod.POST, new HttpEntity<>(body, headers), ApiResponse.class);
            assertThat(completed.getStatusCode()).as("배송 완료 처리 body=%s", completed.getBody())
                    .isEqualTo(HttpStatus.OK);

            assertThat(client.awaitData(AWAIT)).as("5: COMPLETED").contains("\"status\":\"COMPLETED\"");
        }
    }

    @Test
    @DisplayName("A 에서 만든 세션으로 B 의 API 를 그대로 쓸 수 있다")
    void sharesSessionAcrossInstances() {
        // 스티키 세션이 필요 없다는 것 — 세션이 Redis 에 있고 JVM 에 없다는 전제의 검증이다.
        var scenario = fixture.assignedDelivery();
        String riderCookie = loginOnA(RIDER_LOGIN, scenario.riderLoginId());

        var response = rest.exchange(secondary.url("/api/rider/session"), HttpMethod.GET,
                new HttpEntity<>(withCookie(riderCookie)), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
