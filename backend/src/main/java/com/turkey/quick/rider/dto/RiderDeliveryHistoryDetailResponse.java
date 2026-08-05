package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.dto.AddressResponse;
import com.turkey.quick.order.dto.DeliveryStatusStepResponse;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.payment.domain.RiderSettlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 라이더 운행 기록 상세.
 *
 * 고객에게 청구된 운임(FINAL 스냅샷)과 라이더가 받은 정산액을 함께 담는다 —
 * 수수료 정책이 생기면 두 값이 갈리므로, 화면이 "왜 이 금액인지"를 설명할 수 있어야 한다.
 * 완료된 배송이므로 연락처는 담지 않는다(정산 확인에 필요하지 않다).
 */
@Schema(description = "라이더 운행 기록 상세")
public record RiderDeliveryHistoryDetailResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지")
        AddressResponse pickup,

        @Schema(description = "도착지")
        AddressResponse destination,

        @Schema(description = "픽업지-도착지 직선거리(m)", example = "3200")
        Integer straightDistanceMeters,

        @Schema(description = "고객에게 청구된 최종 운임(FINAL 스냅샷)")
        FareBreakdownResponse finalFare,

        @Schema(description = "정산 금액(원)", example = "6400")
        Long settlementAmount,

        @Schema(description = "정산 시각(UTC)", example = "2026-07-28T02:47:00")
        LocalDateTime settledAt,

        @Schema(description = "상태 전이 타임라인")
        List<DeliveryStatusStepResponse> steps,

        @Schema(description = "배송 완료 인증 방식")
        ProofType proofType,

        @Schema(description = "배송 완료 인증 참조값", example = "proof/2026/07/1024.jpg")
        String proofValue
) {
    /**
     * @param finalFare  고객에게 청구된 최종 운임(FINAL 스냅샷). 완료 배송이므로 항상 FINAL 이다.
     * @param settlement 라이더가 받은 정산 내역. 완료 트랜잭션에서 함께 생성되므로 항상 존재한다.
     * @param proof      배송 완료 인증 기록. #61 이후 완료=인증 필수라 보통 존재하지만, 방어적으로 null 허용.
     * @param proofValue 응답에 실을 인증 참조값. proofType=PHOTO 면 호출자가
     *                   {@code RiderDeliveryProofStorage}로 해석한 저장 경로, 그 외 타입이면
     *                   {@code proof.getProofValue()}를 그대로 넘긴다(스토리지 접근은 서비스 계층 책임).
     */
    public static RiderDeliveryHistoryDetailResponse from(DeliveryOrder order, FareBreakdownResponse finalFare,
                                                          RiderSettlement settlement, DeliveryProof proof,
                                                          String proofValue) {
        return new RiderDeliveryHistoryDetailResponse(
                order.getId(),
                order.getItemType(),
                new AddressResponse(order.getPickup().getRoadAddress(), order.getPickup().getDetailAddress(),
                        order.getPickup().getPostalCode(), order.getPickup().getLatitude(),
                        order.getPickup().getLongitude()),
                new AddressResponse(order.getDestination().getRoadAddress(),
                        order.getDestination().getDetailAddress(), order.getDestination().getPostalCode(),
                        order.getDestination().getLatitude(), order.getDestination().getLongitude()),
                order.getStraightDistanceMeters(),
                finalFare,
                settlement.getSettlementAmount(),
                settlement.getSettledAt(),
                DeliveryStatusStepResponse.timelineOf(order),
                proof == null ? null : proof.getProofType(),
                proofValue);
    }
}
