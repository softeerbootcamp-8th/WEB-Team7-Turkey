package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.dto.AddressResponse;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배차 대기 콜 상세(수락 전).
 *
 * 좌표는 지도에 픽업·도착 지점을 찍는 데 필요해 포함하지만, 연락처와 상세 주소(동·호수)는
 * 수락 이후에만 열린다 — 목록과 같은 기준이다.
 */
@Schema(description = "배차 대기 콜 상세")
public record RiderDeliveryRequestDetailResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지(상세 주소는 수락 전까지 비어 있다)")
        AddressResponse pickup,

        @Schema(description = "도착지(상세 주소는 수락 전까지 비어 있다)")
        AddressResponse destination,

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "예상 소요시간(분)", example = "12")
        Integer estimatedMinutes,

        @Schema(description = "고객에게 청구된 예상 운임 분해")
        FareBreakdownResponse estimatedFare,

        @Schema(description = "예상 정산액(원). 수수료 정책이 생기면 운임 합계와 갈릴 수 있다.",
                example = "6400")
        Long expectedSettlementAmount,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt
) {
}
