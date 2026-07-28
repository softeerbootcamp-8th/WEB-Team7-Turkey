package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배송요청 생성 결과. 생성 직후이므로 status 는 항상 WAITING 이고 배차된 라이더는 없다.
 * 같은 requestKey 로 재전송된 요청도 새로 만들지 않고 기존 주문의 이 응답을 그대로 돌려준다.
 */
@Schema(description = "배송요청 생성 결과")
public record DeliveryCreateResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(생성 직후에는 WAITING)")
        OrderStatus status,

        @Schema(description = "요청 멱등키(요청에 실린 값 그대로)",
                example = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34")
        String requestKey,

        @Schema(description = "주문 시점에 확정된 예상 운임(ESTIMATE 스냅샷)")
        FareBreakdownResponse estimatedFare,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt
) {
}
