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
 * 자체 호스팅 OSRM 을 호출하는 {@link RoutingClient} 구현체(#420).
 *
 * <p>서버는 서울특별시 행정구역만 전처리한 것으로 Redis 인스턴스에 co-locate 배포돼 있고
 * (#416, `10.0.130.0:5000`), WAS 보안그룹에서만 접근할 수 있다. <b>개발 PC 에서는 닿지 않는다</b> —
 * 로컬에서 실제 경로가 필요하면 #407 처럼 Docker 로 직접 띄워야 한다. 그래서 이 클래스의 테스트는
 * 실제 서버가 아니라 스텁 HTTP 서버에 붙는다.
 *
 * <h2>타임아웃 (사람 확인, 2026-08-07)</h2>
 * 연결 300ms / 읽기 700ms. #407 로컬 PoC 응답이 10ms 내외였으므로 정상 상황 대비 70배 여유이고,
 * 최악의 경우에도 추적 API 가 1초 이상 밀리지 않는다. 재시도는 하지 않는다 — 응답 경로에 있는
 * 호출이라 재시도가 곧 지연 배증이다.
 *
 * <h2>실패를 다루는 방식</h2>
 * <ul>
 *   <li><b>연결 실패·타임아웃·5xx</b> — 서버가 죽었다고 보고 {@link RoutingFailureBackoff} 에
 *       실패로 센다. 연속 3회면 잠시 호출을 멈춘다.
 *   <li><b>4xx</b> — <b>실패로 세지 않는다.</b> OSRM 은 경로를 못 찾은 경우
 *       ({@code NoRoute}·{@code NoSegment}) 에도 400 을 준다. 서울 밖 좌표가 섞이는 것은 정상적으로
 *       일어나는 일인데, 이걸 장애로 세면 <b>그런 주문 세 건이 모든 고객의 ETA 를 끊는다.</b>
 *   <li><b>200 이지만 {@code code != "Ok"}</b> — 경로가 없는 것이지 서버는 멀쩡하므로 성공으로 센다.
 * </ul>
 * 어느 경우든 호출자에게는 빈 값 하나로만 나간다({@link RoutingClient#findRoute} 계약).
 */
@Slf4j
@Component
public class OsrmRoutingClient implements RoutingClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(700);

    /**
     * {@code geometries=geojson} 은 기본값(encoded polyline)과 달리 디코딩이 필요 없다.
     *
     * <p>{@code overview=simplified} 는 MVP 판단이다(사람 확인, 2026-08-07). {@code full} 은 골목
     * 단위까지 정확하지만 좌표가 수백~수천 개로 늘어 추적 API 응답이 그만큼 커진다 — 화면에서
     * 곡선이 각져 보이는 게 문제가 되면(#422) 그때 올린다. 설정값으로 빼지 않은 것은 지금 고를
     * 근거가 없는 값을 설정 항목으로 만들면 기본값을 정하는 문제가 그대로 남기 때문이다.
     */
    private static final String QUERY = "?geometries=geojson&overview=simplified";

    private final RestClient restClient;
    private final String baseUrl;
    private final RoutingFailureBackoff backoff;

    /**
     * 생성자를 하나만 둔다 — 여러 개면 스프링이 어느 것으로 주입할지 {@code @Autowired} 로 따로
     * 알려 줘야 한다. 테스트는 스텁 서버 주소를 넘겨 그대로 인스턴스를 만든다.
     *
     * <p>{@link RestClient} 를 여기서 직접 만들고 자동설정된 {@code RestClient.Builder} 를 주입받지
     * 않는 이유는 타임아웃 때문이다 — 300/700ms 는 공용으로 쓰기엔 지나치게 짧아서, 공용 빈에
     * 얹으면 다른 호출이 무심코 그 값을 물려받는다.
     */
    public OsrmRoutingClient(@Value("${routing.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.backoff = new RoutingFailureBackoff();
    }

    @Override
    public Optional<Route> findRoute(Coordinate origin, Coordinate destination) {
        if (!backoff.allowsCall()) {
            // 이미 연속 실패로 판정된 구간이라 매번 warn 을 남기면 로그만 채운다.
            log.debug("event=ROUTING_CALL_SKIPPED reason=BACKOFF");
            return Optional.empty();
        }

        URI uri = URI.create(baseUrl + "/route/v1/driving/"
                + origin.toOsrmSegment() + ";" + destination.toOsrmSegment() + QUERY);
        try {
            OsrmResponse response = restClient.get().uri(uri).retrieve().body(OsrmResponse.class);
            backoff.recordSuccess();
            return toRoute(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // 경로 없음(NoRoute/NoSegment)이 여기로 온다. 서버는 정상이므로 실패로 세지 않는다.
                log.info("event=ROUTING_NO_ROUTE status={} body={}",
                        e.getStatusCode().value(), e.getResponseBodyAsString());
                backoff.recordSuccess();
                return Optional.empty();
            }
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED reason=SERVER_ERROR status={}",
                    e.getStatusCode().value());
            return Optional.empty();
        } catch (RuntimeException e) {
            // 연결 거부·타임아웃·응답 파싱 실패. 어느 쪽이든 이 서버는 지금 쓸 수 없는 상태다.
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED reason={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * OSRM 응답을 도메인 타입으로 옮긴다. <b>여기가 {@code [경도, 위도]} 를 뒤집는 유일한 지점이다.</b>
     *
     * <p>{@code static} package-private 인 이유는 {@link RestClient} 없이 파싱만 단위 테스트하기
     * 위해서다 — 이 클래스에서 조용히 깨질 수 있는 것은 HTTP 호출이 아니라 이 변환이다.
     */
    static Optional<Route> toRoute(OsrmResponse response) {
        if (response == null || !"Ok".equals(response.code())
                || response.routes() == null || response.routes().isEmpty()) {
            return Optional.empty();
        }
        OsrmRoute route = response.routes().getFirst();
        if (route.duration() == null || route.distance() == null
                || route.geometry() == null || route.geometry().coordinates() == null) {
            return Optional.empty();
        }
        List<Coordinate> path = route.geometry().coordinates().stream()
                // GeoJSON 은 [경도, 위도] 순서다. Coordinate 는 (위도, 경도) 순서다.
                .map(pair -> new Coordinate(BigDecimal.valueOf(pair.get(1)),
                        BigDecimal.valueOf(pair.get(0))))
                .toList();
        return Optional.of(new Route(
                // duration 은 초 단위 소수(예: 730.7)다. 밀리초까지 살려 두고 표시 단위 반올림은
                // 호출자에게 맡긴다.
                Duration.ofMillis(Math.round(route.duration() * 1000)),
                Math.round(route.distance()),
                path));
    }

    /**
     * OSRM {@code /route} 응답 중 <b>우리가 쓰는 필드만</b> 옮긴 것. legs·waypoints·weight 등은
     * 읽지 않는다({@code @JsonIgnoreProperties} 로 무시).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmResponse(String code, List<OsrmRoute> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRoute(Double duration, Double distance, OsrmGeometry geometry) {
    }

    /** GeoJSON LineString. {@code coordinates} 의 각 원소가 {@code [경도, 위도]} 쌍이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmGeometry(List<List<Double>> coordinates) {
    }
}
