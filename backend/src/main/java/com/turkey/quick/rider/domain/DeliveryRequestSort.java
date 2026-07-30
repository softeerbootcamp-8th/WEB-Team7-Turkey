package com.turkey.quick.rider.domain;

/**
 * 배차 대기 콜 목록 정렬 기준(RiderDeliveryRequestApi 계약과 값이 같다).
 * 라이더 위치를 모르면 DISTANCE 는 계산할 수 없으므로 REQUESTED_AT 으로 대체한다
 * (#55 계약 확정: 위치 없음은 에러가 아니라 graceful degrade).
 */
public enum DeliveryRequestSort {
    DISTANCE,
    FARE,
    REQUESTED_AT;

    public static DeliveryRequestSort from(String value) {
        try {
            return DeliveryRequestSort.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다. sort=" + value);
        }
    }
}
