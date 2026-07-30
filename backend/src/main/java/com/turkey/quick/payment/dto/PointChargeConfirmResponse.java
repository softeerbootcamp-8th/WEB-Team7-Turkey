package com.turkey.quick.payment.dto;

import com.turkey.quick.payment.domain.PointChargeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 포인트 충전 승인 결과(PAID).
 *
 * <p>승인 트랜잭션은 세 가지를 한 번에 한다: point_charge PENDING→PAID, 지갑 잔액 증가,
 * CHARGE 원장 1행 기록. 그래서 승인 후 잔액을 함께 돌려준다 — 화면이 잔액을 다시 조회하지 않아도 된다.
 */
@Schema(description = "포인트 충전 승인 결과")
public record PointChargeConfirmResponse(

        @Schema(description = "충전 식별자", example = "331")
        Long pointChargeId,

        @Schema(description = "충전 상태. 승인 성공 시 PAID.", example = "PAID")
        PointChargeStatus status,

        @Schema(description = "승인 금액. 전액 결제만 있으므로 요청 금액과 같다.", example = "10000")
        long approvedAmount,

        @Schema(description = "승인 반영 후 잔액", example = "22500")
        long balanceAfter,

        @Schema(description = "승인 시각", example = "2026-07-29T04:13:02")
        LocalDateTime approvedAt
) {
}
