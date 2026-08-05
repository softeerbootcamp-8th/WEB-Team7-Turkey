package com.turkey.quick.order.dto;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

        @Schema(description = "배송 완료 인증 참조값. proofType=PHOTO 면 저장소에서 해석한 실제 경로"
                + "(로컬은 절대경로, S3는 객체 URL), 그 외 타입이면 등록값을 그대로 담는다. 완료 전이면 null.",
                example = "https://cdn.example.com/proof/1024.jpg")
        String proofValue,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt,

        @Schema(description = "완료 시각(UTC). 완료 전이면 null.")
        LocalDateTime completedAt,

        @Schema(description = "취소 시각(UTC). 취소되지 않았으면 null.")
        LocalDateTime canceledAt,

        @Schema(description = "취소 사유", example = "고객 단순 변심")
        String cancelReason,

        @Schema(description = "배정된 라이더 이름. 배차 전이면 null.", example = "박라이더")
        String riderName,

        @Schema(description = "배정된 라이더 연락처. 배차 전이면 null.", example = "010-9876-5432")
        String riderPhoneNumber
) {
    /**
     * @param fare       완료 주문은 FINAL, 그 외는 ESTIMATE 스냅샷으로 호출자가 미리 골라 넘긴다
     *                   (스냅샷 조회는 트랜잭션 경계가 있는 서비스 계층 책임).
     * @param proof      완료 인증 기록. 완료 전이면 null.
     * @param proofValue 응답에 실을 인증 참조값. proofType=PHOTO 면 호출자가
     *                   {@code RiderDeliveryProofStorage}로 해석한 저장 경로, 그 외 타입이면
     *                   {@code proof.getProofValue()}를 그대로 넘긴다(스토리지 접근은 서비스 계층 책임).
     */
    public static DeliveryDetailResponse from(DeliveryOrder order, FareBreakdownResponse fare,
                                              DeliveryProof proof, String proofValue) {
        Member rider = order.getAssignedRider() == null ? null : order.getAssignedRider().getMember();

        return new DeliveryDetailResponse(
                order.getId(),
                order.getStatus(),
                order.getItemType(),
                order.getStraightDistanceMeters(),
                new AddressResponse(order.getPickup().getRoadAddress(), order.getPickup().getDetailAddress(),
                        order.getPickup().getPostalCode(), order.getPickup().getLatitude(),
                        order.getPickup().getLongitude()),
                new AddressResponse(order.getDestination().getRoadAddress(),
                        order.getDestination().getDetailAddress(), order.getDestination().getPostalCode(),
                        order.getDestination().getLatitude(), order.getDestination().getLongitude()),
                new ContactResponse(order.getSender().getName(), order.getSender().getPhoneNumber()),
                new ContactResponse(order.getRecipient().getName(), order.getRecipient().getPhoneNumber()),
                fare,
                steps(order),
                proof == null ? null : proof.getProofType(),
                proofValue,
                order.getRequestedAt(),
                order.getCompletedAt(),
                order.getCanceledAt(),
                order.getCancelReason(),
                rider == null ? null : rider.getName(),
                rider == null ? null : rider.getPhoneNumber());
    }

    /**
     * 타임라인을 delivery_order 의 단계별 시각 컬럼에서 파생한다(order_status_history 는 아직
     * 아무 코드도 쓰지 않는다 — DeliveryTrackingQueryService.steps 와 같은 이유).
     * 추적 스냅샷과 달리 CANCELED 도 포함한다 — 상세는 취소된 주문도 보여줘야 하기 때문이다.
     */
    private static List<DeliveryStatusStepResponse> steps(DeliveryOrder order) {
        List<DeliveryStatusStepResponse> result = new ArrayList<>();
        addStep(result, OrderStatus.WAITING, order.getRequestedAt());
        addStep(result, OrderStatus.ASSIGNED, order.getAssignedAt());
        addStep(result, OrderStatus.MOVING_TO_PICKUP, order.getMovingToPickupAt());
        addStep(result, OrderStatus.PICKED_UP, order.getPickedUpAt());
        addStep(result, OrderStatus.DELIVERING, order.getDeliveringAt());
        addStep(result, OrderStatus.COMPLETED, order.getCompletedAt());
        addStep(result, OrderStatus.CANCELED, order.getCanceledAt());
        return List.copyOf(result);
    }

    private static void addStep(List<DeliveryStatusStepResponse> steps, OrderStatus status,
                                LocalDateTime occurredAt) {
        if (occurredAt != null) {
            steps.add(new DeliveryStatusStepResponse(status, occurredAt));
        }
    }
}
