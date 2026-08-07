package com.turkey.quick.common.routing;

import java.time.Duration;
import java.util.List;

/**
 * 두 좌표 사이의 경로 탐색 결과.
 *
 * @param duration       예상 소요 시간. <b>실시간 교통정보가 반영되지 않은 자유흐름 기준</b>이다
 *                       (OSRM 의 한계로, #407 에서 이 트레이드오프를 알고 채택했다). ETA 로 바꾸는
 *                       것(현재 시각 + 이 값)과 그 오차를 어떻게 안내할지는 호출자(#421) 몫이다.
 * @param distanceMeters 경로 거리(미터, 반올림). 요금 계산에 쓰지 않는다 — 요금은
 *                       {@code order} 의 직선거리 기반 정책이 정본이고, 이 값은 화면 표시용이다.
 * @param path           경로 폴리라인. 첫 좌표는 출발지, 마지막 좌표는 도착지에 <b>가장 가까운 도로
 *                       위의 점</b>이라 요청한 좌표와 정확히 같지 않다(OSRM 이 좌표를 도로에
 *                       스냅한다). 상세도는 {@code overview=simplified} 기준이라 곡선 구간이 다소
 *                       각져 있다.
 */
public record Route(Duration duration, long distanceMeters, List<Coordinate> path) {

    public Route {
        path = List.copyOf(path);
    }
}
