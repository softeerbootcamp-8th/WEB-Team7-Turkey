package com.turkey.quick.common.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 자체 호스팅 OSRM 을 호출하는 {@link RoutingClient} 구현체(#420, #431 에서 카카오→OSRM 원복).
 *
 * <p>서버는 서울시 전용 extract 로 Redis 인스턴스에 co-locate 배포돼 있다(#416,
 * {@code 10.0.130.0:5000}). {@code overview=full&geometries=geojson} 으로 경로 좌표까지 받는다
 * (#532 — 고객 추적 화면에 직선거리 대신 실제 도로 경로를 그리기 위해 duration-only였던 계약을
 * 다시 넓혔다). GeoJSON 좌표는 {@code [경도, 위도]} 순인데, {@link Coordinate} 계약은 항상
 * (위도, 경도)라 파싱 시점에 반드시 뒤집는다 — 안 뒤집어도 서울 좌표는 유효 범위라 예외 없이
 * 조용히 엉뚱한 곳에 그려진다(#423).
 *
 * <p>4xx 는 실패로 세지 않는다. OSRM 은 경로를 못 찾은 경우({@code NoRoute}/{@code NoSegment})에도
 * 400 을 주는데, 서울 밖 좌표가 섞이는 것은 정상적으로 일어나는 일이라 장애로 세면 그런 주문 몇 건이
 * 모든 고객의 ETA 를 끊는다. 5xx·타임아웃·연결 실패만 {@link RoutingFailureBackoff} 에 실패로 기록한다.
 */
@Slf4j
@Component
public class OsrmRoutingClient implements RoutingClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(700);
    private static final String QUERY = "?overview=full&geometries=geojson";

    private final RestClient restClient;
    private final String baseUrl;
    private final RoutingFailureBackoff backoff;

    public OsrmRoutingClient(@Value("${routing.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.backoff = new RoutingFailureBackoff();
    }

    @Override
    public Optional<RouteEstimate> findRoute(Coordinate origin, Coordinate destination) {
        if (!backoff.allowsCall()) {
            log.debug("event=ROUTING_CALL_SKIPPED reason=BACKOFF");
            return Optional.empty();
        }

        URI uri = URI.create(baseUrl + "/route/v1/driving/"
                + origin.toLongitudeLatitudeParam() + ";" + destination.toLongitudeLatitudeParam() + QUERY);
        try {
            OsrmResponse response = restClient.get().uri(uri).retrieve().body(OsrmResponse.class);
            backoff.recordSuccess();
            return toRouteEstimate(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.info("event=ROUTING_NO_ROUTE status={} body={}",
                        e.getStatusCode().value(), e.getResponseBodyAsString());
                backoff.recordSuccess();
                return Optional.empty();
            }
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED reason=SERVER_ERROR status={}", e.getStatusCode().value());
            return Optional.empty();
        } catch (RuntimeException e) {
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED reason={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code static} package-private 인 이유는 HTTP 호출 없이 파싱만 단위 테스트하기 위해서다. */
    static Optional<RouteEstimate> toRouteEstimate(OsrmResponse response) {
        if (response == null || !"Ok".equals(response.code())
                || response.routes() == null || response.routes().isEmpty()) {
            return Optional.empty();
        }
        OsrmRoute route = response.routes().getFirst();
        if (route.duration() == null) {
            return Optional.empty();
        }
        Duration duration = Duration.ofMillis(Math.round(route.duration() * 1000));
        return Optional.of(new RouteEstimate(duration, toPath(route.geometry())));
    }

    /** GeoJSON {@code [경도, 위도]} 좌표를 {@link Coordinate}(위도, 경도)로 뒤집는다. */
    private static List<Coordinate> toPath(OsrmGeometry geometry) {
        if (geometry == null || geometry.coordinates() == null) {
            return List.of();
        }
        return geometry.coordinates().stream()
                .filter(point -> point != null && point.size() >= 2)
                .map(point -> new Coordinate(point.get(1), point.get(0)))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmResponse(String code, List<OsrmRoute> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRoute(Double duration, OsrmGeometry geometry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmGeometry(List<List<BigDecimal>> coordinates) {
    }
}
