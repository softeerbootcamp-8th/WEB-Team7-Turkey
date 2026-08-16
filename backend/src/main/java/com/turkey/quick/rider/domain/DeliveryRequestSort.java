package com.turkey.quick.rider.domain;

/**
 * 배차 대기 콜 목록 정렬 기준(RiderDeliveryRequestApi 계약과 값이 같다).
 * 라이더 위치를 모르면 DISTANCE 는 계산할 수 없으므로 REQUESTED_AT 으로 대체한다
 * (#55 계약 확정: 위치 없음은 에러가 아니라 graceful degrade). DELIVERY_DISTANCE(픽업→도착지
 * 배송거리, #522)는 라이더 위치와 무관하게 항상 계산 가능해 이 대체가 필요 없다.
 */
public enum DeliveryRequestSort {
    DISTANCE,
    FARE,
    REQUESTED_AT,
    DELIVERY_DISTANCE;

    public static DeliveryRequestSort from(String value) {
        try {
            return DeliveryRequestSort.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다. sort=" + value);
        }
    }
}
