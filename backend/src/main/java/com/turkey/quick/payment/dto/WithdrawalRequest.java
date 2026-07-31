package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 라이더 포인트 출금 요청.
 *
 * <p>계좌 정보를 받지 않는다 — 송금 계좌는 등록된 rider_payout_account 가 정본이고,
 * 요청 시점의 값이 rider_withdrawal 에 스냅샷으로 복사된다. 요청 바디로 계좌를 받으면
 * 등록하지 않은 계좌로 송금시킬 통로가 생긴다.
 *
 * <p>출금은 선차감이다: 요청 즉시 잔액이 줄고 WITHDRAWAL 원장이 남으며, 송금 실패 시
 * WITHDRAWAL_REFUND 로 복구된다. {@code requestKey} 는 원장의 전역 유니크 멱등키다.
 */
@Schema(description = "포인트 출금 요청")
public record WithdrawalRequest(

        @Schema(description = "출금 요청 멱등키(UUID). 재전송 시 같은 값을 그대로 보낸다.",
                example = "8f3d2b71-0c4e-4a19-9d55-1e7a3c6b40aa")
        @NotBlank
        @Size(min = 36, max = 36)
        String requestKey,

        @Schema(description = "출금 금액(포인트). 잔액을 초과하면 거부된다.", example = "50000")
        @Positive
        long amount
) {
}
