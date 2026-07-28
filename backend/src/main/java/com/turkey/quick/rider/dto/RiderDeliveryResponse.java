package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.AddressResponse;
import com.turkey.quick.order.dto.ContactResponse;
import com.turkey.quick.order.dto.DeliveryStatusStepResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 라이더의 진행 중 배송 1건.
 *
 * 조회와 단계 전이가 같은 타입을 돌려준다 — 전이 후 화면이 다시 조회하지 않아도 되고,
 * 새로고침·재로그인 후에도 이 응답 하나로 진행 배송 화면을 복구할 수 있다.
 *
 * 배차가 확정된 상태이므로 연락처와 상세 주소를 포함한다(콜 목록·상세와 다른 지점).
 */
@Schema(description = "라이더 진행 중 배송")
public record RiderDeliveryResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태")
        OrderStatus status,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지")
        AddressResponse pickup,

        @Schema(description = "도착지")
        AddressResponse destination,

        @Schema(description = "보내는 사람")
        ContactResponse sender,

        @Schema(description = "받는 사람")
        ContactResponse recipient,

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "예상 정산액(원)", example = "6400")
        Long expectedSettlementAmount,

        @Schema(description = "상태 전이 타임라인(도달한 단계만)")
        List<DeliveryStatusStepResponse> steps,

        @Schema(description = "배차 시각(UTC)", example = "2026-07-28T02:12:00")
        LocalDateTime assignedAt
) {
}
