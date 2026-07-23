package com.turkey.quick.rider.domain;

/**
 * 라이더 운행 상태. 배송 상태(OrderStatus)와는 별개의 축이다.
 *
 * UNAVAILABLE(운행 종료/로그아웃) · AVAILABLE(배차 가능) · BUSY(배송 수행 중)
 *
 * 허용 전이:
 *  - UNAVAILABLE → AVAILABLE (운행 시작)
 *  - AVAILABLE   → UNAVAILABLE (운행 종료)
 *  - AVAILABLE   → BUSY (배차 확정)
 *  - BUSY        → AVAILABLE (배송 완료)
 */
public enum OperatingStatus {
    UNAVAILABLE,
    AVAILABLE,
    BUSY
}
