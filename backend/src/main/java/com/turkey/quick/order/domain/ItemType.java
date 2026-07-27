package com.turkey.quick.order.domain;

/** 배송 물품 종류. 요금 할증 기준이자 주문의 물품 구분값이다(크기·무게·수량 기준은 쓰지 않는다). */
public enum ItemType {
    DOCUMENT,
    SMALL_PARCEL,
    MEDIUM_PARCEL,
    LARGE_PARCEL,
    FOOD
}
