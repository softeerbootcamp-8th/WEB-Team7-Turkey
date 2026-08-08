package com.turkey.quick.common.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오모빌리티 자동차 길찾기 API 를 호출하는 {@link RoutingClient} 구현체(#431).
 *
 * <p>#420 에서 자체 호스팅 OSRM 으로 먼저 갔던 것을 교체한 것이다 — 당시엔 카카오 API 사용 권한
 * 심사가 끝나지 않았다. 심사가 끝나 카카오로 확정했고(사람 확인, 2026-08-07), OSRM 구현체는
 * 이 이슈에서 삭제했다(되돌릴 일이 있으면 git 이력에서 꺼낸다 — 이 저장소가 #297→#317 에서 실제로
 * 그렇게 되돌린 전례가 있다). 자체 인프라(#416/#407) 운영과 좌표 extract 갱신 부담이 사라지고
 * <b>실시간 교통정보가 반영된</b> 소요시간을 받는 것이 바꾼 이유다.
 *
 * <h2>과금이 이 클래스의 설계 제약이다</h2>
 * 호출당 과금이라 <b>테스트는 어떤 층에서도 실제 카카오 API 를 부르지 않는다</b>(사람 확인,
 * 2026-08-07). 단위 테스트는 JDK {@code HttpServer} 스텁에 붙고, 통합 테스트 설정은 아무도 듣지 않는
 * 주소를 가리킨다. 아래 「실패를 다루는 방식」에서 4xx 를 실패로 세는 것도 같은 이유가 절반이다 —
 * 429(쿼터 초과)에 계속 두드리면 과금·차단으로 이어진다.
 *
 * <h2>타임아웃 (사람 확인, 2026-08-07)</h2>
 * 연결 1초 / 읽기 2초. #420 의 300/700ms 는 VPC 안 OSRM 전제였는데, 여기는 공인망 HTTPS 라
 * TLS 핸드셰이크만으로도 300ms 를 넘길 수 있다. 재시도는 하지 않는다 — 응답 경로에 있는 호출이라
 * 재시도가 곧 지연 배증이다. <b>실측 없이 정한 잠정값</b>이고 부하 테스트에서 재조정한다.
 *
 * <h2>실패를 다루는 방식 — OSRM 과 판정이 뒤집혔다</h2>
 * 카카오는 <b>경로를 찾지 못한 것도 HTTP 200 + {@code result_code != 0}</b> 으로 준다. OSRM 이
 * {@code NoRoute}·{@code NoSegment} 에 400 을 주던 것과 정반대다. 그래서 #420 이 "4xx 를 장애로 세지
 * 않는다"고 했던 근거(서울 밖 좌표가 정상적으로 400 을 부른다)가 사라졌고, 여기서는 반대로 잡는다:
 * <ul>
 *   <li><b>200 이지만 {@code result_code != 0}</b> — 경로가 없는 것이지 서버는 멀쩡하다. 성공으로 센다.
 *   <li><b>4xx</b> — <b>실패로 센다.</b> 401(키 불량)·403(권한)·429(쿼터 초과)가 여기로 오는데,
 *       셋 다 계속 불러 봐야 결과가 달라지지 않고 429 는 두드릴수록 손해다.
 *   <li><b>연결 실패·타임아웃·5xx</b> — 실패로 센다.
 * </ul>
 * 연속 3회 실패면 {@link RoutingFailureBackoff} 가 잠시 호출을 멈춘다. 어느 경우든 호출자에게는
 * 빈 값 하나로만 나간다({@link RoutingClient#findRoute} 계약).
 */
@Slf4j
@Component
public class KakaoMobilityRoutingClient implements RoutingClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final String PATH = "/v1/directions";

    /**
     * {@code priority=RECOMMEND} 는 카카오 기본값이지만 명시한다 — 기본값이 바뀌면 ETA 가 조용히
     * 달라지는 종류의 값이라, 무엇을 기준으로 뽑은 경로인지 코드에 남겨 둔다. {@code TIME}(최단시간)이
     * 아니라 추천 경로인 것은 라이더가 실제로 탈 법한 경로가 ETA 로도 맞기 때문이다.
     *
     * <p>{@code summary} 는 지정하지 않는다(기본 false). {@code summary=true} 로 경로 좌표를 빼면
     * 응답과 파싱이 줄지만 {@link Route#path()} 가 항상 비게 되고, 화면이 경로선을 요구할 때(#422)
     * 좌표 뒤집기를 그때 새로 쓰면서 같은 실수를 다시 할 여지가 생긴다(사람 확인, 2026-08-07).
     */
    private static final String QUERY_SUFFIX = "&priority=RECOMMEND";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final RoutingFailureBackoff backoff;

    /**
     * 생성자를 하나만 둔다 — 여러 개면 스프링이 어느 것으로 주입할지 {@code @Autowired} 로 따로
     * 알려 줘야 한다. 테스트는 스텁 서버 주소와 아무 키나 넘겨 그대로 인스턴스를 만든다.
     *
     * <p>{@link RestClient} 를 여기서 직접 만들고 자동설정된 {@code RestClient.Builder} 를 주입받지
     * 않는 이유는 타임아웃 때문이다 — 1/2초는 공용으로 쓰기엔 짧아서, 공용 빈에 얹으면 다른 호출이
     * 무심코 그 값을 물려받는다.
     *
     * <p><b>API 키가 비어 있어도 기동은 성공한다.</b> 비밀정보라 저장소에 없고(CLAUDE.md), 로컬·CI 는
     * 대부분 키 없이 돈다 — 여기서 기동을 막으면 키와 무관한 작업까지 전부 멈춘다. 대신 기동 시
     * 경고를 한 번 남긴다. 호출 시점마다 남기면 로그만 채우고, 아무 말도 안 하면 배포 서버에서
     * 키 누락이 "ETA 가 조용히 안 나옴"으로만 드러난다.
     */
    public KakaoMobilityRoutingClient(
            @Value("${routing.base-url}") String baseUrl,
            @Value("${routing.api-key:}") String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (this.apiKey.isEmpty()) {
            log.warn("event=ROUTING_API_KEY_MISSING message=경로 탐색을 호출하지 않습니다. ETA 는 항상 비어 있습니다");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.backoff = new RoutingFailureBackoff();
    }

    @Override
    public Optional<Route> findRoute(Coordinate origin, Coordinate destination) {
        if (apiKey.isEmpty()) {
            // 기동 시 이미 경고했다. 호출마다 반복하지 않는다.
            log.debug("event=ROUTING_CALL_SKIPPED reason=NO_API_KEY");
            return Optional.empty();
        }
        if (!backoff.allowsCall()) {
            // 이미 연속 실패로 판정된 구간이라 매번 warn 을 남기면 로그만 채운다.
            log.debug("event=ROUTING_CALL_SKIPPED reason=BACKOFF");
            return Optional.empty();
        }

        URI uri = URI.create(baseUrl + PATH
                + "?origin=" + origin.toLongitudeLatitudeParam()
                + "&destination=" + destination.toLongitudeLatitudeParam()
                + QUERY_SUFFIX);
        try {
            KakaoDirectionsResponse response = restClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(KakaoDirectionsResponse.class);
            backoff.recordSuccess();
            return toRoute(response);
        } catch (RestClientResponseException e) {
            // 4xx 도 여기서 실패로 센다 — 카카오는 "경로 없음"을 200 으로 주므로 4xx 는 인증·쿼터
            // 같은 진짜 오류다. 응답 본문에 사유가 있으므로 함께 남긴다.
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED status={} body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RuntimeException e) {
            // 연결 거부·타임아웃·응답 파싱 실패. 어느 쪽이든 지금은 이 API 를 쓸 수 없는 상태다.
            backoff.recordFailure();
            log.warn("event=ROUTING_CALL_FAILED reason={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 카카오 응답을 도메인 타입으로 옮긴다. <b>여기가 {@code [경도, 위도]} 를 뒤집는 유일한 지점이다.</b>
     *
     * <p>{@code static} package-private 인 이유는 {@link RestClient} 없이 파싱만 단위 테스트하기
     * 위해서다 — 이 클래스에서 조용히 깨질 수 있는 것은 HTTP 호출이 아니라 이 변환이다.
     */
    static Optional<Route> toRoute(KakaoDirectionsResponse response) {
        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            return Optional.empty();
        }
        KakaoRoute route = response.routes().getFirst();
        if (route.resultCode() == null || route.resultCode() != 0) {
            // 서비스 지역 밖·출발지와 도착지가 너무 가까움 등. 서버는 정상이라 호출자에겐 "경로 없음"이다.
            log.info("event=ROUTING_NO_ROUTE resultCode={} resultMsg={}",
                    route.resultCode(), route.resultMsg());
            return Optional.empty();
        }
        KakaoSummary summary = route.summary();
        if (summary == null || summary.duration() == null || summary.distance() == null) {
            return Optional.empty();
        }
        return Optional.of(new Route(
                // 카카오는 초 단위 정수다(OSRM 은 소수였다). 밀리초 보정이 필요 없다.
                Duration.ofSeconds(summary.duration()),
                summary.distance(),
                pathOf(route.sections())));
    }

    /**
     * {@code sections[].roads[].vertexes} 를 이어 붙여 폴리라인을 만든다.
     *
     * <p>{@code vertexes} 는 좌표 쌍의 목록이 아니라 <b>{@code [x1,y1,x2,y2,...]} 로 평탄화된 배열</b>
     * 이고 {@code x} 가 경도다. 그래서 인덱스를 2씩 넘기며 짝을 다시 만든다 — 홀수 개가 오면
     * (있을 수 없지만) 마지막 하나는 짝이 없으므로 버린다. 도로 경계에서 앞 road 의 끝점과 다음
     * road 의 시작점이 겹치는 경우가 있는데, 폴리라인으로 그릴 때 무해해서 걷어내지 않는다.
     */
    private static List<Coordinate> pathOf(List<KakaoSection> sections) {
        if (sections == null) {
            return List.of();
        }
        List<Coordinate> path = new ArrayList<>();
        for (KakaoSection section : sections) {
            if (section == null || section.roads() == null) {
                continue;
            }
            for (KakaoRoad road : section.roads()) {
                if (road == null || road.vertexes() == null) {
                    continue;
                }
                List<Double> vertexes = road.vertexes();
                for (int i = 0; i + 1 < vertexes.size(); i += 2) {
                    Double longitude = vertexes.get(i);
                    Double latitude = vertexes.get(i + 1);
                    if (longitude == null || latitude == null) {
                        continue;
                    }
                    path.add(new Coordinate(BigDecimal.valueOf(latitude),
                            BigDecimal.valueOf(longitude)));
                }
            }
        }
        return path;
    }

    /**
     * 카카오 길찾기 응답 중 <b>우리가 쓰는 필드만</b> 옮긴 것. {@code trans_id}·{@code guides}·
     * {@code bound}·{@code fare} 등은 읽지 않는다({@code @JsonIgnoreProperties} 로 무시).
     *
     * <p>{@code result_code} 처럼 스네이크케이스인 필드는 {@code @JsonProperty} 로 짚어 준다 —
     * 이 애플리케이션은 Jackson 네이밍 전략을 바꾸지 않아 기본(카멜케이스)으로 읽는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoDirectionsResponse(List<KakaoRoute> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoRoute(
            @JsonProperty("result_code") Integer resultCode,
            @JsonProperty("result_msg") String resultMsg,
            KakaoSummary summary,
            List<KakaoSection> sections) {
    }

    /** {@code distance} 는 미터, {@code duration} 은 초. 둘 다 정수다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoSummary(Integer distance, Integer duration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoSection(List<KakaoRoad> roads) {
    }

    /** {@code vertexes} 는 {@code [경도, 위도, 경도, 위도, ...]} 로 평탄화된 배열이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoRoad(List<Double> vertexes) {
    }
}
