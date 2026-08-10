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
import org.junit.jupiter.api.Test;

/**
 * JDK 내장 {@link HttpServer} 로 OSRM 응답을 흉내 낸다 — 실제 소켓·HTTP·Jackson 변환을 지나가므로
 * 타임아웃·URL 형식·파싱이 실제로 맞는지 잡힌다.
 */
@DisplayName("OSRM 라우팅 클라이언트")
class OsrmRoutingClientTest {

    private static final Coordinate SEOUL_CITY_HALL =
            new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9779000"));
    private static final Coordinate GANGNAM_STATION =
            new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));

    private static final String OK_RESPONSE = """
            {"code":"Ok","routes":[{"duration":730.7,"distance":9945.2}]}
            """;

    private HttpServer server;
    private final List<String> receivedUris = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

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

    @Test
    @DisplayName("좌표를 {경도},{위도} 순서로 싣고 overview=false 를 붙인다")
    void sendsCoordinatesInLongitudeLatitudeOrderWithoutGeometry() {
        stubResponse(200, OK_RESPONSE);

        clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

        assertThat(receivedUris).singleElement().asString()
                .startsWith("/route/v1/driving/126.9779000,37.5665000;127.0276000,37.4979000")
                .contains("overview=false");
    }

    @Test
    @DisplayName("소요시간을 밀리초까지 살려 Duration 으로 옮긴다")
    void parsesDuration() {
        stubResponse(200, OK_RESPONSE);

        Optional<Duration> duration = clientForStub().findRoute(SEOUL_CITY_HALL, GANGNAM_STATION);

        assertThat(duration).contains(Duration.ofMillis(730_700));
    }

    @Test
    @DisplayName("경로를 찾지 못한 200(code != Ok)과 4xx 는 둘 다 빈 값이고 백오프에 걸리지 않는다")
    void treatsNoRouteAsEmptyWithoutTrippingBackoff() {
        stubResponse(400, """
                {"code":"NoSegment","message":"Could not find a matching segment"}
                """);
        OsrmRoutingClient client = clientForStub();

        int calls = RoutingFailureBackoff.FAILURE_THRESHOLD + 2;
        for (int i = 0; i < calls; i++) {
            assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        assertThat(receivedUris).hasSize(calls);
    }

    @Test
    @DisplayName("연결할 수 없으면 예외 대신 빈 값을 돌려준다")
    void returnsEmptyWhenServerUnreachable() {
        // 포트 1 은 특권 포트라 아무도 듣고 있지 않다.
        OsrmRoutingClient client = new OsrmRoutingClient("http://localhost:1");

        assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
    }

    @Test
    @DisplayName("서버 오류(5xx)가 임계치만큼 이어지면 그 뒤 호출은 서버에 닿지 않는다")
    void stopsCallingAfterConsecutiveServerErrors() {
        stubResponse(500, "boom");
        OsrmRoutingClient client = clientForStub();

        for (int i = 0; i < RoutingFailureBackoff.FAILURE_THRESHOLD + 3; i++) {
            assertThat(client.findRoute(SEOUL_CITY_HALL, GANGNAM_STATION)).isEmpty();
        }

        assertThat(receivedUris).hasSize(RoutingFailureBackoff.FAILURE_THRESHOLD);
    }
}
