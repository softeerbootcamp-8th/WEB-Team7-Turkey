package com.turkey.quick.payment.dto;

import com.turkey.quick.payment.domain.PaymentMethod;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 포인트 충전 요청 결과(PENDING).
 *
 * <p>잔액을 담지 않는다 — 이 단계에서는 아직 지갑이 변하지 않았기 때문이다.
 * 화면이 잔액을 갱신해야 할 시점은 승인 응답({@link PointChargeConfirmResponse})이다.
 */
@Schema(description = "포인트 충전 요청 결과")
public record PointChargeResponse(

        @Schema(description = "충전 식별자", example = "331")
        Long pointChargeId,

        @Schema(description = "충전 상태. 요청 직후는 항상 PENDING.", example = "PENDING")
        PointChargeStatus status,

        @Schema(description = "요청 금액", example = "10000")
        long requestedAmount,

        @Schema(description = "결제 수단", example = "CARD")
        PaymentMethod paymentMethod,

        @Schema(description = "요청 시각", example = "2026-07-29T04:12:33")
        LocalDateTime requestedAt
) {
    /** 준비 단계의 두 경로({@code CustomerPaymentService}·{@code PointChargePreparer})가 함께 쓴다. */
    public static PointChargeResponse from(PointCharge pointCharge) {
        return new PointChargeResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getRequestedAmount(),
                pointCharge.getPaymentMethod(),
                pointCharge.getRequestedAt());
    }
}
