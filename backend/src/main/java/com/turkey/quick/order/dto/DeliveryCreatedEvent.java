package com.turkey.quick.order.dto;

import java.math.BigDecimal;

/**
 * 배송요청 생성 완료 이벤트. API 응답이 아니라 내부 도메인 이벤트라 {@code @Schema}를 붙이지 않는다.
 *
 * <p>{@code order.service.DeliveryService}가 발행하고, 트랜잭션 커밋 후에만
 * {@code location.service.DeliveryLocationEventListener}가 픽업 좌표를 GEO에 반영한다 — 주문 생성이
 * 롤백되면 이 이벤트도 발행되지 않으므로, 존재하지 않는 주문이 GEO에 남는 불일치가 생기지 않는다.
 */
public record DeliveryCreatedEvent(Long deliveryId, BigDecimal pickupLatitude, BigDecimal pickupLongitude) {
}
