package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 라이더 정산 내역 한 줄(rider_settlement 1행).
 *
 * <p>정산은 배송 완료 트랜잭션에서 최종 운임 스냅샷을 근거로 생성되며, 같은 트랜잭션에서
 * SETTLEMENT 원장 1행과 지갑 적립이 함께 일어난다. 따라서 정산 목록과 포인트 거래 내역은
 * 같은 사건을 다른 관점에서 본 것이다 — 정산 목록은 "배송별 수익", 거래 내역은 "잔액 변화".
 */
@Schema(description = "라이더 정산 내역 항목")
public record SettlementResponse(

        @Schema(description = "정산 식별자", example = "77")
        Long settlementId,

        @Schema(description = "정산 대상 배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "정산 금액(라이더 수익)", example = "7650")
        long settlementAmount,

        @Schema(description = "정산 생성 시각", example = "2026-07-29T04:12:33")
        LocalDateTime settledAt
) {
}
