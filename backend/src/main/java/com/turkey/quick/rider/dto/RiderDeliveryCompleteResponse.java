package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.OperatingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배송 완료 결과.
 *
 * 배송 DELIVERING→COMPLETED, 라이더 BUSY→AVAILABLE, 정산 생성이 한 트랜잭션이므로
 * 세 결과를 함께 돌려준다. 정산 행의 존재 자체가 정산 완료를 뜻하므로 별도 정산 상태는 없다 —
 * 정산 생성이 실패하면 완료 자체가 롤백되고 이 응답은 나가지 않는다.
 *
 * <p>{@code proofUploadFailed}는 멀티파트 직접 업로드 경로 전용이다(사람 확인, DB 커밋 후
 * S3 업로드 순서 전환). 배송 완료(정산·포인트 적립 포함)는 이미 커밋돼 되돌리지 않고, 그 뒤에
 * 시도한 S3 업로드만 실패한 경우 이 플래그로 알린다 — {@code true}면 배송은 완료됐지만
 * {@code delivery_proof.proof_value}가 가리키는 사진이 실제로는 없는 상태다. 재업로드 API는
 * 아직 없다(다음 이슈).
 */
@Schema(description = "배송 완료 결과")
public record RiderDeliveryCompleteResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(완료 시 COMPLETED)")
        OrderStatus status,

        @Schema(description = "라이더 운행 상태(완료 시 AVAILABLE)")
        OperatingStatus operatingStatus,

        @Schema(description = "확정된 정산 금액(원)", example = "6400")
        Long settlementAmount,

        @Schema(description = "완료 시각(UTC)", example = "2026-07-28T02:47:00")
        LocalDateTime completedAt,

        @Schema(description = "배송 완료는 확정됐으나 인증 사진 업로드가 사후에 실패했는지 여부. "
                + "true면 사진이 없는 채로 완료된 상태 — 재업로드 수단은 아직 없다.",
                example = "false")
        boolean proofUploadFailed
) {
    public RiderDeliveryCompleteResponse withProofUploadFailed(boolean failed) {
        return new RiderDeliveryCompleteResponse(
                deliveryId, status, operatingStatus, settlementAmount, completedAt, failed);
    }
}
