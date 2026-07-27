package com.turkey.quick.order.domain;

/**
 * 운임 스냅샷의 종류.
 * ESTIMATE 는 배송요청 생성 시점의 예상 요금, FINAL 은 배송 완료 시점의 최종 요금이다.
 * 주문당 각 종류 최대 1건(uk_order_fare_type)이며, FINAL 스냅샷이 정산 금액의 근거가 된다.
 */
public enum FareType {
    ESTIMATE,
    FINAL
}
