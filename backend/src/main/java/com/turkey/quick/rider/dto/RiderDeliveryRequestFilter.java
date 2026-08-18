package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ItemType;

/**
 * 콜 목록 조회(#60/#522)의 운임·배송거리 범위 필터 + 물품 종류 필터. 전부 선택값이며, {@code null}은
 * "그 방향/기준 제한 없음"을 뜻한다. 반경(라이더→픽업지) 필터는 이 레코드에 없다 — 기존
 * {@code radiusMeters}를 그대로 쓴다.
 *
 * <p>{@code itemType}은 범위가 아니라 정확히 일치하는 값만 남기는 단일 선택 필터다(#522 계약
 * 확정 — 다중 선택은 지금 화면 요구에 없어 범위를 좁혔다).
 *
 * <p>여러 필드가 같은 타입 계열(Long/Integer)이라 평범한 메서드 인자로 나열하면 순서를 헷갈리기
 * 쉬워, 이 레코드로 묶어 실수를 막는다.
 */
public record RiderDeliveryRequestFilter(
        Long fareMin, Long fareMax, Integer distanceMin, Integer distanceMax, ItemType itemType) {
}
