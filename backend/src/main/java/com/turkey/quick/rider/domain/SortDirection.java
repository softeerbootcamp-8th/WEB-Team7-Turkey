package com.turkey.quick.rider.domain;

/**
 * 배차 대기 콜 목록 정렬 방향(#60). 생략하면 정렬 기준별 기본 방향을 쓴다 — DISTANCE·REQUESTED_AT은
 * 오름차순(가까운/오래된 순), FARE는 내림차순(높은 순)이 #55/#367부터의 기본값이었다.
 */
public enum SortDirection {
    ASC,
    DESC;

    public static SortDirection from(String value) {
        if (value == null) {
            return null;
        }
        try {
            return SortDirection.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 정렬 방향입니다. sortDirection=" + value);
        }
    }
}
