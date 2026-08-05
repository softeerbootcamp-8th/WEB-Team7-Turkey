package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 라이더 운행 기록 목록의 한 행.
 *
 * <p>배송 자체 정보만 담는다 — 정산액·수익 등 금액 정보는 포인트 화면(/api/rider/points/*)이
 * 담당하므로 여기 넣지 않는다(배송 기록·포인트 화면 분리). 정산이 생성된 완료 배송만 담으므로
 * {@code status} 는 사실상 COMPLETED 고정이지만, 이슈 명세의 "주문 상태"를 계약에 명시하고
 * 화면 뱃지를 데이터로 그리기 위해 필드로 노출한다(취소는 배차 전에만 발생해 라이더 기록에 없다).
 */
@Schema(description = "라이더 운행 기록 목록 항목")
public record RiderDeliveryHistoryItemResponse(

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

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "완료 시각(UTC)", example = "2026-07-28T02:47:00")
        LocalDateTime completedAt
) {
    public static RiderDeliveryHistoryItemResponse from(DeliveryOrder order) {
        return new RiderDeliveryHistoryItemResponse(
                order.getId(),
                order.getStatus(),
                order.getItemType(),
                order.getPickup().getRoadAddress(),
                order.getDestination().getRoadAddress(),
                order.getStraightDistanceMeters(),
                order.getCompletedAt());
    }
}
