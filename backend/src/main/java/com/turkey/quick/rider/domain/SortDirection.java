package com.turkey.quick.rider.domain;

/**
 * 배차 대기 콜 목록 정렬 방향. 정렬 기준별 고정값이라 요청 파라미터로 받지 않는다(#522, 이전에는
 * {@code sortDirection} 파라미터로 오버라이드를 허용했으나 라이더가 실제로 고를 정렬 프리셋이
 * "픽업거리·배송거리 오름차순/요금 내림차순" 셋으로 확정되며 오버라이드가 필요 없어졌다).
 * DISTANCE·DELIVERY_DISTANCE·REQUESTED_AT은 오름차순(가까운/짧은/오래된 순), FARE는
 * 내림차순(높은 순) 고정이다.
 */
public enum SortDirection {
    ASC,
    DESC
}
