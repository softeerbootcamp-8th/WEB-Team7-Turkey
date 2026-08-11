package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 라이더 포인트 출금 요청.
 *
 * <p>계좌 정보를 요청 바디로 받는다(사람 확인, 2026-08-11). 사전 등록 계좌(rider_payout_account)
 * 방식 대신 신청 시점 입력을 택했다 — #87(계좌 등록 API)이 아직 없어 등록 계좌 방식으로는 출금이
 * 항상 409로 막혀 있었다. 원본 계좌번호는 서비스 계층에서 마스킹 후 즉시 버리고
 * rider_withdrawal 에는 마스킹된 스냅샷만 남긴다({@code RiderPaymentService#requestWithdrawal}).
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
        long amount,

        @Schema(description = "은행 코드", example = "004")
        @NotBlank
        @Size(max = 20)
        String bankCode,

        @Schema(description = "계좌번호(숫자만). 원본은 저장하지 않고 마스킹 후 폐기한다.",
                example = "12345678901234")
        @NotBlank
        @Pattern(regexp = "\\d{6,20}", message = "계좌번호는 6~20자리 숫자여야 합니다")
        String accountNumber,

        @Schema(description = "예금주명", example = "홍길동")
        @NotBlank
        @Size(max = 50)
        String accountHolderName
) {
}
