package com.turkey.quick.payment.dto;

import com.turkey.quick.payment.domain.PointDirection;
import com.turkey.quick.payment.domain.PointTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 포인트 거래 내역 한 줄(point_transaction 원장 1행).
 *
 * <p>원장은 append-only 라 수정 시각이 없다 — {@code createdAt} 하나만 노출한다.
 *
 * <p>소스 FK 는 유형별로 정확히 하나만 채워진다(DB ck_point_transaction_source). 화면이
 * "배송 상세로 이동" 같은 링크를 그릴 수 있게 id 를 그대로 내보내되, 해당 없는 필드는 null 이다.
 */
@Schema(description = "포인트 거래 내역 항목")
public record PointTransactionResponse(

        @Schema(description = "거래 식별자", example = "9012")
        Long transactionId,

        @Schema(description = "거래 유형", example = "ORDER_USE")
        PointTransactionType transactionType,

        @Schema(description = "증감 방향. 유형에서 파생되며 별도로 지정되지 않는다.", example = "DEBIT")
        PointDirection direction,

        @Schema(description = "거래 금액(항상 양수). 부호는 direction 이 나타낸다.", example = "8500")
        long amount,

        @Schema(description = "거래 직후 잔액", example = "4000")
        long balanceAfter,

        @Schema(description = "연관 배송요청 식별자(ORDER_USE·ORDER_REFUND 만)", example = "1024")
        Long deliveryId,

        @Schema(description = "연관 충전 식별자(CHARGE·CHARGE_REFUND 만)", example = "331")
        Long pointChargeId,

        @Schema(description = "연관 정산 식별자(SETTLEMENT 만)", example = "77")
        Long settlementId,

        @Schema(description = "연관 출금 식별자(WITHDRAWAL·WITHDRAWAL_REFUND 만)", example = "12")
        Long withdrawalId,

        @Schema(description = "거래 발생 시각", example = "2026-07-29T04:12:33")
        LocalDateTime createdAt
) {
}
