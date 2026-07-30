package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 포인트 충전 승인 요청.
 *
 * <p>금액을 받지 않는다 — 승인 금액은 항상 PENDING 행의 requested_amount 전액이다(부분 결제 없음).
 * 클라이언트가 금액을 보내게 두면 요청 금액과 다른 값으로 승인시킬 통로가 생긴다.
 *
 * <p>{@code providerPaymentKey} 는 PG 승인 식별자이며 유니크 제약이 중복 승인을 막는다.
 * MVP 모킹 흐름에서는 생략 가능하고, 서버가 모의 키를 채운다.
 */
@Schema(description = "포인트 충전 승인 요청")
public record PointChargeConfirmRequest(

        @Schema(description = "PG 승인 식별자. MVP 모킹에서는 생략 가능(서버가 모의 키 생성).",
                example = "mock_pay_9f2c41")
        @Size(max = 100)
        String providerPaymentKey
) {
}
