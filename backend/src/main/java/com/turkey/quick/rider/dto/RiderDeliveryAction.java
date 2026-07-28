package com.turkey.quick.rider.dto;

/**
 * 진행 중 배송에서 라이더가 수행하는 단계 전이 행위.
 *
 * 목표 상태를 받지 않는 이유는 {@link RiderOperatingAction} 과 같다 — 전이 가능 여부는
 * 현재 상태가 정하고, 요청은 "무엇을 했는지"만 말한다. 단계를 건너뛰는 요청은 서버가 거부한다.
 *
 * 완료(DELIVERING→COMPLETED)는 여기 없다 — 인증 자료와 정산 생성이 함께 필요해 전용 API 로 분리했다.
 */
public enum RiderDeliveryAction {

    /** 픽업 출발하기: ASSIGNED → MOVING_TO_PICKUP */
    START_MOVING_TO_PICKUP,

    /** 픽업 완료하기: MOVING_TO_PICKUP → PICKED_UP */
    PICK_UP,

    /** 배송 출발하기: PICKED_UP → DELIVERING */
    START_DELIVERING
}
