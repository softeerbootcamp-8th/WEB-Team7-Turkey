package com.turkey.quick.rider.dto;

/**
 * 라이더가 직접 수행하는 운행 상태 변경 행위.
 *
 * 상태 값(AVAILABLE/UNAVAILABLE)을 그대로 받지 않는 이유: 상태는 "요청 값으로 덮어쓰지 않고
 * 현재 상태 + 수행 행위로 검증한다"가 팀 규칙이다. 행위로 받으면 BUSY 인 라이더가
 * AVAILABLE 을 밀어 넣어 진행 중 배송을 두고 이탈하는 요청 자체가 표현되지 않는다.
 *
 * BUSY 로의 전이는 여기 없다 — 배차 확정(accept)과 배송 완료(complete)의 부수 효과로만 일어난다.
 */
public enum RiderOperatingAction {

    /** 콜 받기: UNAVAILABLE → AVAILABLE ({@code RiderProfile#goOnline}) */
    GO_ONLINE,

    /** 운행 종료: AVAILABLE → UNAVAILABLE ({@code RiderProfile#goOffline}) */
    GO_OFFLINE
}
