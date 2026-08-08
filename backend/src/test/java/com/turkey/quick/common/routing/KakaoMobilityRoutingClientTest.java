package com.turkey.quick.common.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 카카오모빌리티 라우팅 클라이언트 단위 테스트(#431).
 *
 * <p><b>실제 카카오 API 를 절대 부르지 않는다</b>(사람 확인, 2026-08-07). 호출당 과금이라 CI·로컬
 * 어디서도 테스트 실행이 돈으로 이어지면 안 된다. 그래서 삭제한 {@code OsrmRoutingClientTest} 가
 * 쓰던 방식을 그대로 잇는다 — JDK 내장 {@link HttpServer} 로 카카오 응답 형식을 흉내 낸다. 새
 * 의존성(WireMock 등) 없이 <b>실제 소켓·실제 HTTP·실제 Jackson 변환</b>을 지나가므로, 타임아웃
 * 설정이나 스네이크케이스 필드 매핑처럼 목킹으로는 검증되지 않는 것이 잡힌다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않아 단위 테스트 층에 둔다.
 */
@DisplayName("카카오모빌리티 라우팅 클라이언트")
class KakaoMobilityRoutingClientTest {

    /** 서울시청. 카카오는 이 좌표를 {@code x=126.9779, y=37.5665} 로 표기한다. */
    private static final Coordinate SEOUL_CITY_HALL =
            new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9779000"));

    /** 강남역. */
    private static final Coordinate GANGNAM_STATION =
            new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));

    private static final String API_KEY = "test-key-never-sent-to-kakao";

    /**
     * 실제 응답에서 우리가 읽는 필드만 남기고, 읽지 않는 필드({@code trans_id}·{@code bound}·
     * {@code fare}·{@code guides})도 일부 섞어 둔다 — {@code @JsonIgnoreProperties} 가 실제로
     * 걸려 있는지 확인하기 위해서다. {@code vertexes} 가 <b>평탄 배열</b>인 것과 road 가 둘로
     * 나뉘어 있는 것이 이 픽스처의 핵심이다.
     */
    private static final String OK_RESPONSE = """
            {"trans_id":"0195f3e3",
             "routes":[{"result_code":0,"result_msg":"길찾기 성공",
                        "summary":{"origin":{"name":"","x":126.9779,"y":37.5665},
                                   "destination":{"name":"","x":127.0276,"y":37.4979},
                                   "waypoints":[],"priority":"RECOMMEND",
                                   "bound":{"min_x":126.97,"min_y":37.49,"max_x":127.03,"max_y":37.57},
                                   "fare":{"taxi":8300,"toll":0},
                                   "distance":9945,"duration":1500},
                        "sections":[{"distance":9945,"duration":1500,
                                     "roads":[{"name":"세종대로","distance":500,"duration":80,
                                               "traffic_speed":22.0,"traffic_state":1,
                                               "vertexes":[126.9779,37.5665,126.99,37.52]},
                                              {"name":"강남대로","distance":9445,"duration":1420,
                                               "traffic_speed":18.0,"traffic_state":2,
                                               "vertexes":[126.99,37.52,127.0276,37.4979]}],
                                     "guides":[]}]}]}
            """;

    private HttpServer server;
    private final List<String> receivedUris = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuthorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStubServer() throws IOException {
        // 포트 0 = 비어 있는 포트를 OS 가 골라 준다. 고정 포트를 쓰면 병렬 실행·개발 서버와 충돌한다.
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    /** 스텁이 항상 같은 응답을 주게 하고, 받은 요청 URI·인증 헤더를 기록한다. */
    private void stubResponse(int status, String body) {
        stub(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void stub(HttpHandler handler) {
        server.createContext("/", exchange -> {
            receivedUris.add(exchange.getRequestURI().toString());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedAuthorizations.add(authorization == null ? "" : authorization);
            handler.handle(exchange);
        });
    }

    private KakaoMobilityRoutingClient clientForStub() {
        return clientForStub(API_KEY);
    }

    private KakaoMobilityRoutingClient clientForStub(String apiKey) {
        return new KakaoMobilityRoutingClient(
                "http://localhost:" + server.getAddress().getPort(), apiKey);
    }

    @Nested
    @DisplayName("요청")
    class RequestTest {

        @Test
        @DisplayName("좌표를 {경도},{위도} 순서로 싣고 길찾기 경로로 보낸다")
        void shouldSendCoordinatesInLongitudeLatitudeOrder() {
            stubResponse(200, OK_RESPONSE);

            clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            // 위경도를 뒤집어도 서울 좌표는 둘 다 유효 범위라 어떤 예외도 나지 않는다.
            // 그래서 실제로 나간 URL 을 직접 확인하는 것 말고는 이 실수를 잡을 방법이 없다.
            assertThat(receivedUris).singleElement().asString()
                    .startsWith("/v1/directions?")
                    .contains("origin=126.9779000,37.5665000")
                    .contains("destination=127.0276000,37.4979000")
                    .contains("priority=RECOMMEND");
        }

        @Test
        @DisplayName("KakaoAK 인증 헤더를 붙인다")
        void shouldSendKakaoAkAuthorizationHeader() {
            stubResponse(200, OK_RESPONSE);

            clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            // 헤더 형식을 틀리면 운영에서 모든 호출이 401 이 되는데, 클라이언트는 실패를 빈 값으로
            // 삼켜 "ETA 가 안 나온다"로만 드러난다. 여기서 고정해 둔다.
            assertThat(receivedAuthorizations).singleElement().isEqualTo("KakaoAK " + API_KEY);
        }

        @Test
        @DisplayName("API 키가 없으면 호출 자체를 하지 않는다")
        void shouldNotCallApiWhenKeyIsMissing() {
            stubResponse(200, OK_RESPONSE);

            assertThat(clientForStub("").findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
            assertThat(clientForStub("   ").findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();

            // 키 없이 부르면 401 만 받는다. 과금·쿼터가 걸린 API 라 소켓을 열지도 않는 것이 맞다.
            assertThat(receivedUris).isEmpty();
        }
    }

    @Nested
    @DisplayName("정상 응답")
    class SuccessTest {

        @Test
        @DisplayName("소요시간(초)과 거리(m)를 응답 그대로 옮긴다")
        void shouldParseDurationAndDistance() {
            stubResponse(200, OK_RESPONSE);

            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            assertThat(route).isPresent();
            // 카카오 duration 은 초 단위 정수다(OSRM 은 소수였다). 1500초 = 25분.
            assertThat(route.get().duration()).isEqualTo(Duration.ofSeconds(1500));
            assertThat(route.get().distanceMeters()).isEqualTo(9945);
        }

        @Test
        @DisplayName("평탄 배열 vertexes 를 (위도, 경도) 좌표로 뒤집어 담고 road 를 이어 붙인다")
        void shouldFlattenAndFlipVertexes() {
            stubResponse(200, OK_RESPONSE);

            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            assertThat(route).isPresent();
            List<Coordinate> path = route.get().path();
            // road 두 개 × 좌표 두 쌍 = 4개. 경계에서 겹치는 점(126.99,37.52)도 그대로 남긴다.
            assertThat(path).hasSize(4);
            // vertexes 의 첫 두 값은 [126.9779, 37.5665] 였다. 위도는 37 대, 경도는 126 대여야 한다.
            assertThat(path.getFirst().latitude()).isEqualByComparingTo("37.5665");
            assertThat(path.getFirst().longitude()).isEqualByComparingTo("126.9779");
            assertThat(path.getLast().latitude()).isEqualByComparingTo("37.4979");
            assertThat(path.getLast().longitude()).isEqualByComparingTo("127.0276");
        }

        @Test
        @DisplayName("경로를 찾지 못한 200 응답(result_code != 0)은 빈 값이다")
        void shouldReturnEmptyWhenResultCodeIsNotZero() {
            // 카카오는 OSRM 과 달리 '경로 없음'을 HTTP 200 + 본문 코드로 준다.
            stubResponse(200, """
                    {"trans_id":"0195f3e3",
                     "routes":[{"result_code":104,"result_msg":"출발지와 도착지가 너무 가까움"}]}
                    """);

            assertThat(clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패 경로 — 호출자에게 예외가 나가지 않는다")
    class FailureTest {

        @Test
        @DisplayName("라우팅 서버에 연결할 수 없으면 예외 대신 빈 값을 돌려준다")
        void shouldReturnEmptyWhenServerUnreachable() {
            // 포트 1 은 특권 포트라 아무도 듣고 있지 않다 — 연결이 즉시 거부된다.
            var client = new KakaoMobilityRoutingClient("http://localhost:1", API_KEY);

            assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        @Test
        @DisplayName("응답이 오지 않으면 읽기 타임아웃 뒤 빈 값을 돌려준다")
        void shouldReturnEmptyOnReadTimeout() {
            stub(exchange -> {
                try {
                    // 읽기 타임아웃(2초)보다 길게 붙잡아 두되, 테스트가 끝난 뒤까지 스레드가
                    // 남지 않도록 너무 길게 잡지는 않는다.
                    Thread.sleep(4_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            long startedAt = System.nanoTime();
            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(route).isEmpty();
            // 타임아웃이 실제로 걸렸는지 본다. 설정을 놓쳐 무한정 기다리면 추적 API 전체가 멈춘다.
            assertThat(elapsed).isLessThan(Duration.ofMillis(3_500));
        }

        @Test
        @DisplayName("서버 오류(5xx)도 빈 값이다")
        void shouldReturnEmptyOnServerError() {
            stubResponse(500, "boom");

            assertThat(clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        @Test
        @DisplayName("인증 실패(401)도 빈 값이다")
        void shouldReturnEmptyOnUnauthorized() {
            stubResponse(401, """
                    {"code":-401,"msg":"Cannot find Authorization : KakaoAK header"}
                    """);

            assertThat(clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        @Test
        @DisplayName("깨진 JSON 이 와도 빈 값이다")
        void shouldReturnEmptyOnMalformedBody() {
            stubResponse(200, "not json at all");

            assertThat(clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }
    }

    @Nested
    @DisplayName("연속 실패 시 호출 차단")
    class BackoffTest {

        @Test
        @DisplayName("서버 오류가 임계치만큼 이어지면 그 뒤 호출은 서버에 닿지 않는다")
        void shouldStopCallingAfterConsecutiveFailures() {
            stubResponse(500, "boom");
            KakaoMobilityRoutingClient client = clientForStub();

            for (int i = 0; i < RoutingFailureBackoff.FAILURE_THRESHOLD + 5; i++) {
                assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
            }

            // 대기 시간(1초) 안에 반복하므로, 임계치를 넘긴 호출은 소켓을 열지도 않는다.
            assertThat(receivedUris).hasSize(RoutingFailureBackoff.FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("쿼터 초과(429)도 장애로 세어 호출을 멈춘다")
        void shouldStopCallingOnQuotaExceeded() {
            // OSRM 시절과 판정이 뒤집힌 지점이다. 그때는 4xx 가 '경로 없음'이라 세지 않았지만,
            // 카카오는 경로 없음을 200 으로 주므로 4xx 는 진짜 오류다. 특히 429 는 호출당 과금
            // API 를 계속 두드리는 것이라 물러서야 한다. 이 테스트가 그 뒤집힘의 회귀 방어다.
            stubResponse(429, """
                    {"code":-10,"msg":"quota exceeded"}
                    """);
            KakaoMobilityRoutingClient client = clientForStub();

            for (int i = 0; i < RoutingFailureBackoff.FAILURE_THRESHOLD + 5; i++) {
                assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
            }

            assertThat(receivedUris).hasSize(RoutingFailureBackoff.FAILURE_THRESHOLD);
        }
    }

    @Nested
    @DisplayName("응답 변환 자체의 방어")
    class ResponseMappingTest {

        @Test
        @DisplayName("routes 가 비었거나 result_code·summary 가 없는 응답은 빈 값으로 흡수한다")
        void shouldReturnEmptyForIncompletePayloads() {
            assertThat(KakaoMobilityRoutingClient.toRoute(null)).isEmpty();
            assertThat(KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(null))).isEmpty();
            assertThat(KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(List.of()))).isEmpty();
            // result_code 자체가 없는 응답(파싱은 됐지만 형식이 다른 경우)
            assertThat(KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(List.of(
                            new KakaoMobilityRoutingClient.KakaoRoute(
                                    null, null, summary(9945, 1500), List.of()))))).isEmpty();
            // 성공 코드인데 summary 가 없음
            assertThat(KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(List.of(
                            new KakaoMobilityRoutingClient.KakaoRoute(
                                    0, "길찾기 성공", null, List.of()))))).isEmpty();
        }

        @Test
        @DisplayName("sections 가 없어도 소요시간·거리만으로 경로를 만든다")
        void shouldBuildRouteWithEmptyPathWhenSectionsAbsent() {
            // summary=true 로 부르거나 응답 형식이 바뀌어 경로가 빠져도 ETA 는 살아야 한다.
            // 지금 호출자(#421)가 쓰는 것은 duration 뿐이다.
            Optional<Route> route = KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(List.of(
                            new KakaoMobilityRoutingClient.KakaoRoute(
                                    0, "길찾기 성공", summary(9945, 1500), null))));

            assertThat(route).isPresent();
            assertThat(route.get().duration()).isEqualTo(Duration.ofSeconds(1500));
            assertThat(route.get().path()).isEmpty();
        }

        @Test
        @DisplayName("vertexes 개수가 홀수면 짝이 없는 마지막 값을 버린다")
        void shouldDropUnpairedVertex() {
            // 있을 수 없는 응답이지만, 여기서 인덱스를 잘못 다루면 IndexOutOfBounds 가 추적 API
            // 전체를 500 으로 만든다 — 이 클라이언트는 어떤 입력에도 예외를 내보내면 안 된다.
            var road = new KakaoMobilityRoutingClient.KakaoRoad(
                    List.of(126.9779, 37.5665, 127.0276));
            Optional<Route> route = KakaoMobilityRoutingClient.toRoute(
                    new KakaoMobilityRoutingClient.KakaoDirectionsResponse(List.of(
                            new KakaoMobilityRoutingClient.KakaoRoute(0, "길찾기 성공",
                                    summary(9945, 1500),
                                    List.of(new KakaoMobilityRoutingClient.KakaoSection(
                                            List.of(road)))))));

            assertThat(route).isPresent();
            assertThat(route.get().path()).hasSize(1);
            assertThat(route.get().path().getFirst().latitude()).isEqualByComparingTo("37.5665");
        }

        private KakaoMobilityRoutingClient.KakaoSummary summary(int distance, int duration) {
            return new KakaoMobilityRoutingClient.KakaoSummary(distance, duration);
        }
    }
}
