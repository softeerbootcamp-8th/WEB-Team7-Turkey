package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배차 대기 콜 목록의 한 행.
 *
 * 수락 전이므로 연락처와 상세 주소를 담지 않는다 — 아직 계약 관계가 없는 라이더에게
 * 고객 개인정보를 노출할 이유가 없다. 도로명 주소까지만 보여주고, 나머지는 수락 이후에 열린다.
 */
@Schema(description = "배차 대기 콜 목록 항목")
public record RiderDeliveryRequestSummaryResponse(

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

        @Schema(description = "라이더 최신 위치에서 픽업지까지의 거리(m). 위치를 모르면 null.",
                example = "740")
        Integer distanceToPickupMeters,

        @Schema(description = "예상 정산액(원)", example = "6400")
        Long expectedSettlementAmount,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt
) {
}
