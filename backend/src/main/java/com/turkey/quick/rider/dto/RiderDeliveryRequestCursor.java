package com.turkey.quick.rider.dto;

import java.time.LocalDateTime;

/**
 * 콜 목록 조회(#60/#522) keyset(커서) 페이지네이션의 커서. 이전 페이지 마지막 항목의 (정렬 기준 값,
 * deliveryId) 쌍이다 — {@code afterId}만으로는 그 사이 다른 라이더가 수락해 목록에서 사라진
 * 항목의 "다음 위치"를 못 찾을 수 있어, 정렬 기준 값도 함께 받는다.
 *
 * <p>{@code sort} 파라미터에 해당하는 값 필드 하나만 채우면 된다(예: {@code sort=FARE}면
 * {@code afterFare}). 첫 페이지 요청이면 전부 {@code null}이다.
 *
 * <p>{@code afterDistanceMeters}(라이더→픽업지 거리, sort=DISTANCE)와
 * {@code afterDeliveryDistanceMeters}(픽업→도착지 배송거리, sort=DELIVERY_DISTANCE, #522)는
 * 이름이 비슷해 보이지만 서로 다른 값을 가리키므로 섞어 쓰면 안 된다.
 */
public record RiderDeliveryRequestCursor(
        Integer afterDistanceMeters, Long afterFare, LocalDateTime afterRequestedAt,
        Integer afterDeliveryDistanceMeters, Long afterId) {
}
