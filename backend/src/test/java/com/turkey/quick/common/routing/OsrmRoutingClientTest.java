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
 * OSRM 클라이언트 단위 테스트(#420).
 *
 * <p>실제 OSRM 은 배포 VPC 안에만 있어 개발 PC·CI 에서 닿지 않는다(#416). 그래서 JDK 내장
 * {@link HttpServer} 로 응답을 흉내 낸다 — 새 의존성(WireMock 등) 없이 <b>실제 소켓·실제 HTTP·실제
 * Jackson 변환</b>을 지나가므로, 타임아웃 설정이나 응답 파싱처럼 목킹으로는 검증되지 않는 것이 잡힌다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않아 단위 테스트 층에 둔다.
 */
@DisplayName("OSRM 라우팅 클라이언트")
class OsrmRoutingClientTest {

    /** 서울시청. GeoJSON 은 이 좌표를 {@code [126.9779, 37.5665]} 로 표기한다. */
    private static final Coordinate SEOUL_CITY_HALL =
            new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9779000"));

    /** 강남역. #416 이 배포 검증에 쓴 것과 같은 구간이다(9,945.2m / 730.7초). */
    private static final Coordinate GANGNAM_STATION =
            new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));

    private static final String OK_RESPONSE = """
            {"code":"Ok",
             "routes":[{"geometry":{"coordinates":[[126.9779,37.5665],[126.99,37.52],[127.0276,37.4979]],
                                    "type":"LineString"},
                        "legs":[{"steps":[],"summary":"","weight":730.7,"duration":730.7,"distance":9945.2}],
                        "weight_name":"routability","weight":730.7,"duration":730.7,"distance":9945.2}],
             "waypoints":[{"hint":"abc","distance":4.5,"name":"세종대로","location":[126.9779,37.5665]}]}
            """;

    private HttpServer server;
    private final List<String> receivedUris = new CopyOnWriteArrayList<>();

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

    /** 스텁이 항상 같은 응답을 주게 하고, 받은 요청 URI 를 기록한다. */
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
            handler.handle(exchange);
        });
    }

    private OsrmRoutingClient clientForStub() {
        return new OsrmRoutingClient("http://localhost:" + server.getAddress().getPort());
    }

    @Nested
    @DisplayName("정상 응답")
    class SuccessTest {

        @Test
        @DisplayName("요청 경로에 좌표를 {경도},{위도} 순서로 싣는다")
        void shouldSendCoordinatesInLongitudeLatitudeOrder() {
            stubResponse(200, OK_RESPONSE);

            clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            // 위경도를 뒤집어도 서울 좌표는 둘 다 유효 범위라 어떤 예외도 나지 않는다.
            // 그래서 실제로 나간 URL 을 직접 확인하는 것 말고는 이 실수를 잡을 방법이 없다.
            assertThat(receivedUris).singleElement().asString()
                    .startsWith("/route/v1/driving/126.9779000,37.5665000;127.0276000,37.4979000")
                    .contains("geometries=geojson")
                    .contains("overview=simplified");
        }

        @Test
        @DisplayName("소요시간과 거리를 응답 그대로 옮긴다")
        void shouldParseDurationAndDistance() {
            stubResponse(200, OK_RESPONSE);

            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            assertThat(route).isPresent();
            // 730.7초 = 12분 10.7초. 초 단위로 버리지 않고 밀리초까지 살린다.
            assertThat(route.get().duration()).isEqualTo(Duration.ofMillis(730_700));
            assertThat(route.get().distanceMeters()).isEqualTo(9945);   // 9945.2 반올림
        }

        @Test
        @DisplayName("GeoJSON 의 [경도, 위도] 를 (위도, 경도) 로 뒤집어 담는다")
        void shouldFlipGeoJsonCoordinateOrder() {
            stubResponse(200, OK_RESPONSE);

            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

            assertThat(route).isPresent();
            List<Coordinate> path = route.get().path();
            assertThat(path).hasSize(3);
            // 응답의 첫 좌표는 [126.9779, 37.5665] 였다. 위도는 37 대, 경도는 126 대여야 한다.
            assertThat(path.getFirst().latitude()).isEqualByComparingTo("37.5665");
            assertThat(path.getFirst().longitude()).isEqualByComparingTo("126.9779");
            assertThat(path.getLast().latitude()).isEqualByComparingTo("37.4979");
            assertThat(path.getLast().longitude()).isEqualByComparingTo("127.0276");
        }

        @Test
        @DisplayName("경로를 찾지 못한 200 응답(code != Ok)은 빈 값이다")
        void shouldReturnEmptyWhenCodeIsNotOk() {
            stubResponse(200, """
                    {"code":"NoRoute","message":"Impossible route between points"}
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
            OsrmRoutingClient client = new OsrmRoutingClient("http://localhost:1");

            assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        @Test
        @DisplayName("응답이 오지 않으면 읽기 타임아웃 뒤 빈 값을 돌려준다")
        void shouldReturnEmptyOnReadTimeout() {
            stub(exchange -> {
                try {
                    // 읽기 타임아웃(700ms)보다 길게 붙잡아 두되, 테스트가 끝난 뒤까지 스레드가
                    // 남지 않도록 너무 길게 잡지는 않는다.
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            long startedAt = System.nanoTime();
            Optional<Route> route = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(route).isEmpty();
            // 타임아웃이 실제로 걸렸는지 본다. 설정을 놓쳐 무한정 기다리면 추적 API 전체가 멈춘다.
            assertThat(elapsed).isLessThan(Duration.ofMillis(1_500));
        }

        @Test
        @DisplayName("서버 오류(5xx)도 빈 값이다")
        void shouldReturnEmptyOnServerError() {
            stubResponse(500, "boom");

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
            OsrmRoutingClient client = clientForStub();

            for (int i = 0; i < RoutingFailureBackoff.FAILURE_THRESHOLD + 5; i++) {
                assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
            }

            // 대기 시간(1초) 안에 반복하므로, 임계치를 넘긴 호출은 소켓을 열지도 않는다.
            assertThat(receivedUris).hasSize(RoutingFailureBackoff.FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("경로 없음(4xx)은 서버 장애가 아니므로 호출을 차단하지 않는다")
        void shouldKeepCallingWhenServerRejectsCoordinates() {
            // 서울시 전용 extract 라 서울 밖 좌표는 400 NoSegment 로 돌아온다. 정상적으로 생기는
            // 일이라 이걸 장애로 세면 그런 주문 세 건이 모든 고객의 ETA 를 끊는다.
            stubResponse(400, """
                    {"code":"NoSegment","message":"Could not find a matching segment for coordinate 0"}
                    """);
            OsrmRoutingClient client = clientForStub();

            int calls = RoutingFailureBackoff.FAILURE_THRESHOLD + 2;
            for (int i = 0; i < calls; i++) {
                assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
            }

            assertThat(receivedUris).hasSize(calls);
        }
    }

    @Nested
    @DisplayName("응답 변환 자체의 방어")
    class ResponseMappingTest {

        @Test
        @DisplayName("routes 가 비었거나 필드가 누락된 응답은 빈 값으로 흡수한다")
        void shouldReturnEmptyForIncompletePayloads() {
            assertThat(OsrmRoutingClient.toRoute(null)).isEmpty();
            assertThat(OsrmRoutingClient.toRoute(
                    new OsrmRoutingClient.OsrmResponse("Ok", List.of()))).isEmpty();
            assertThat(OsrmRoutingClient.toRoute(
                    new OsrmRoutingClient.OsrmResponse("Ok", null))).isEmpty();
            assertThat(OsrmRoutingClient.toRoute(new OsrmRoutingClient.OsrmResponse("Ok",
                    List.of(new OsrmRoutingClient.OsrmRoute(730.7, 9945.2, null))))).isEmpty();
        }
    }
}
