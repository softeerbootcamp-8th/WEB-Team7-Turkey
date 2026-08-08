package com.turkey.quick.common.routing;

import java.util.Optional;

/**
 * 경로 탐색 포트 — 두 좌표 사이의 예상 소요시간과 경로 좌표를 얻는다(#420).
 *
 * <p><b>인터페이스로 끊은 이유가 실제로 값을 했다</b>: #407 에서 카카오 API 사용 권한 심사가 끝나지
 * 않아 자체 호스팅 OSRM 으로 먼저 갔는데, 심사가 끝난 뒤 #431 에서 구현체를
 * ({@link KakaoMobilityRoutingClient}) 갈아 끼웠고 <b>이 포트도 호출자
 * ({@code DeliveryRouteEstimator}, #421)도 한 줄도 바뀌지 않았다.</b>
 * {@code payment/service/PaymentGateway} 와 같은 성격의 포트다.
 *
 * <p><b>패키지 위치가 {@code common} 인 이유</b>: {@code PaymentGateway}·{@code SmsSender} 는 소비자와
 * 같은 도메인 패키지에 두는 관례지만, 이 포트는 시그니처에 도메인 타입이 하나도 없는 순수 인프라
 * 어댑터다. 게다가 첫 소비자는 {@code order}({@code DeliveryTrackingQueryService}, #421)인데
 * {@code location → order} 방향 의존이 이미 있어, 어느 도메인 패키지에 넣어도 방향이 엇갈린다.
 * {@code common/config/S3Config} 가 특정 도메인에 속하지 않는 것과 같은 자리다.
 *
 * <p><b>이 포트가 담당하지 않는 것</b>
 * <ul>
 *   <li>ETA 산정 — 소요시간을 언제의 시각에 더할지, 픽업 전/후로 구간을 어떻게 잡을지는 도메인
 *       정책이라 호출자(#421)가 정한다.
 *   <li>캐싱 — 같은 주문의 경로를 얼마나 재사용할지는 호출 주기를 아는 쪽이 정해야 한다.
 *       현재는 캐시가 없다.
 *   <li>재시도 — 아래 「실패해도 던지지 않는다」 참고. 응답 경로에 있는 호출이라 재시도로 지연을
 *       배로 늘리지 않는다.
 * </ul>
 */
public interface RoutingClient {

    /**
     * 출발지에서 도착지까지 자동차 경로를 찾는다.
     *
     * <p><b>실패해도 예외를 던지지 않는다</b>(#420 요구사항). 이 호출은 고객 추적 API 응답 경로에
     * 들어가므로(#421), 라우팅 서버 장애가 추적 화면 자체를 못 여는 장애로 번지면 안 된다 —
     * "ETA 가 안 보이는 화면"보다 "열리지 않는 화면"이 훨씬 나쁘다. 그래서 타임아웃·연결 실패·
     * 서버 오류·경로 없음을 <b>전부 빈 값 하나로 뭉갠다.</b> 호출자는 "ETA 없음"으로 처리하면 된다.
     *
     * <p>그 대가로 호출자는 실패 사유를 알 수 없다. 사유별 대응(예: 서비스 지역 밖 안내)이 필요해지면
     * 그때 반환 타입을 넓히고, 그전까지는 로그와 지표로만 구분한다.
     *
     * @return 경로. 찾지 못했거나 라우팅 서버를 호출할 수 없으면 빈 값
     */
    Optional<Route> findRoute(Coordinate origin, Coordinate destination);
}
