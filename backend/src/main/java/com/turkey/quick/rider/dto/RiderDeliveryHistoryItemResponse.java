package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 라이더 운행 기록 목록의 한 행.
 * 정산이 생성된 완료 배송만 담는다(취소 주문은 배차 전에만 발생하므로 라이더 이력에 남지 않는다).
 */
@Schema(description = "라이더 운행 기록 목록 항목")
public record RiderDeliveryHistoryItemResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지 도로명 주소", example = "서울 강남구 테헤란로 152")
        String pickupRoadAddress,

        @Schema(description = "도착지 도로명 주소", example = "서울 송파구 올림픽로 300")
        String destinationRoadAddress,

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "정산 금액(원)", example = "6400")
        Long settlementAmount,

        @Schema(description = "완료 시각(UTC)", example = "2026-07-28T02:47:00")
        LocalDateTime completedAt
) {
}
