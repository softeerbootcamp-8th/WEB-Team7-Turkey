package com.turkey.quick.payment.dto;

import com.turkey.quick.rider.domain.RiderWithdrawal;
import com.turkey.quick.rider.domain.WithdrawalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 출금 요청 결과 및 내역 항목.
 *
 * <p>계좌는 요청 시점 스냅샷을 그대로 노출한다 — 나중에 라이더가 계좌를 바꿔도 과거 출금 내역의
 * 표시가 흔들리지 않아야 한다. 계좌번호는 마스킹된 값만 나가며 평문은 어떤 응답에도 담지 않는다.
 */
@Schema(description = "포인트 출금 내역 항목")
public record WithdrawalResponse(

        @Schema(description = "출금 식별자", example = "12")
        Long withdrawalId,

        @Schema(description = "출금 상태", example = "PENDING")
        WithdrawalStatus status,

        @Schema(description = "출금 금액", example = "50000")
        long amount,

        @Schema(description = "요청 시점 은행 코드 스냅샷", example = "088")
        String bankCode,

        @Schema(description = "요청 시점 마스킹 계좌번호 스냅샷", example = "110-***-****23")
        String maskedAccountNumber,

        @Schema(description = "요청 시점 예금주 스냅샷", example = "김라이더")
        String accountHolderName,

        @Schema(description = "실패 사유(FAILED 만)", example = "예금주 불일치")
        String failureReason,

        @Schema(description = "요청 시각", example = "2026-07-29T04:12:33")
        LocalDateTime requestedAt,

        @Schema(description = "처리 완료 시각(COMPLETED·FAILED 만)", example = "2026-07-29T05:00:11")
        LocalDateTime processedAt
) {
    /** 신청·조회 두 경로({@code RiderPaymentService}·{@code WithdrawalRequester})가 함께 쓴다. */
    public static WithdrawalResponse from(RiderWithdrawal withdrawal) {
        return new WithdrawalResponse(
                withdrawal.getId(),
                withdrawal.getStatus(),
                withdrawal.getAmount(),
                withdrawal.getBankCodeSnapshot(),
                withdrawal.getMaskedAccountNumberSnapshot(),
                withdrawal.getAccountHolderNameSnapshot(),
                withdrawal.getFailureReason(),
                withdrawal.getRequestedAt(),
                withdrawal.getProcessedAt());
    }

}
