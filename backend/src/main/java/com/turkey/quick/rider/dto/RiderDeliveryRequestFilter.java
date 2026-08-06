package com.turkey.quick.rider.dto;

/**
 * 콜 목록 조회(#60)의 운임·배송거리 범위 필터. 전부 선택값이며, {@code null}은 "그 방향 제한 없음"을
 * 뜻한다. 반경(라이더→픽업지) 필터는 이 레코드에 없다 — 기존 {@code radiusMeters}를 그대로 쓴다.
 *
 * <p>4개 필드가 전부 같은 타입 계열(Long/Integer)이라 평범한 메서드 인자로 나열하면 순서를 헷갈리기
 * 쉬워, 이 레코드로 묶어 실수를 막는다.
 */
public record RiderDeliveryRequestFilter(Long fareMin, Long fareMax, Integer distanceMin, Integer distanceMax) {
}
