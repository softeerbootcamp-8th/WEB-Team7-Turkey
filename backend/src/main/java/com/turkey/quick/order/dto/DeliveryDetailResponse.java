package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송요청 상세(고객). 주문 당사자에게만 내려주므로 양쪽 연락처를 모두 포함한다.
 *
 * 배송 완료 인증은 참조값만 담는다 — delivery_proof 는 이미지 바이너리를 저장하지 않고
 * URL/스토리지 키/인증코드만 보관한다.
 */
@Schema(description = "배송요청 상세")
public record DeliveryDetailResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태")
        OrderStatus status,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "픽업지")
        AddressResponse pickup,

        @Schema(description = "도착지")
        AddressResponse destination,

        @Schema(description = "보내는 사람")
        ContactResponse sender,

        @Schema(description = "받는 사람")
        ContactResponse recipient,

        @Schema(description = "운임. 완료 주문은 FINAL, 그 전이면 ESTIMATE 스냅샷이다.")
        FareBreakdownResponse fare,

        @Schema(description = "상태 전이 타임라인(도달한 단계만)")
        List<DeliveryStatusStepResponse> steps,

        @Schema(description = "배송 완료 인증 방식. 완료 전이면 null.")
        ProofType proofType,

        @Schema(description = "배송 완료 인증 참조값(사진 URL/키, 인증코드 등). 완료 전이면 null.",
                example = "https://cdn.example.com/proof/1024.jpg")
        String proofValue,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt,

        @Schema(description = "완료 시각(UTC). 완료 전이면 null.")
        LocalDateTime completedAt,

        @Schema(description = "취소 시각(UTC). 취소되지 않았으면 null.")
        LocalDateTime canceledAt,

        @Schema(description = "취소 사유", example = "고객 단순 변심")
        String cancelReason
) {
}
