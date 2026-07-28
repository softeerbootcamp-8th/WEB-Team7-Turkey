package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배송요청 취소 결과. 취소가 성공하면 status 는 항상 CANCELED 다.
 * 포인트 환불이 붙는다면 환불 금액을 이 응답에 더한다(차감·환불 시점은 아직 미확정).
 */
@Schema(description = "배송요청 취소 결과")
public record DeliveryCancelResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(취소 성공 시 CANCELED)")
        OrderStatus status,

        @Schema(description = "취소 시각(UTC)", example = "2026-07-28T02:12:00")
        LocalDateTime canceledAt
) {
}
