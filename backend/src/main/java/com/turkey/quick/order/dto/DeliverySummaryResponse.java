package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 이용기록 목록의 한 행. 목록에서는 주소 전문·연락처·타임라인을 싣지 않는다(상세에서 조회).
 * 금액은 완료 주문이면 FINAL, 그 전이면 ESTIMATE 스냅샷의 합계다.
 */
@Schema(description = "배송요청 목록 항목")
public record DeliverySummaryResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태")
        OrderStatus status,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지 도로명 주소", example = "서울 강남구 테헤란로 152")
        String pickupRoadAddress,

        @Schema(description = "도착지 도로명 주소", example = "서울 송파구 올림픽로 300")
        String destinationRoadAddress,

        @Schema(description = "합계 운임(원)", example = "6400")
        Long totalFare,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt,

        @Schema(description = "완료 시각(UTC). 완료 전이면 null.", example = "2026-07-28T02:47:00")
        LocalDateTime completedAt
) {
    /** @param totalFare 호출자가 미리 골라 넘긴 운임(완료면 FINAL, 아니면 ESTIMATE) 합계. */
    public static DeliverySummaryResponse from(DeliveryOrder order, Long totalFare) {
        return new DeliverySummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getItemType(),
                order.getPickup().getRoadAddress(),
                order.getDestination().getRoadAddress(),
                totalFare,
                order.getRequestedAt(),
                order.getCompletedAt());
    }
}
