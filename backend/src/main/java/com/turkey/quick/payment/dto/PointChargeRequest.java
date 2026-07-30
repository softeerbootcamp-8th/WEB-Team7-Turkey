package com.turkey.quick.payment.dto;

import com.turkey.quick.payment.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 포인트 충전 요청(PENDING 생성).
 *
 * <p>이 시점에는 잔액이 늘지 않는다 — PENDING 인 point_charge 행만 만들어지고, 잔액 반영은
 * 승인(confirm) 트랜잭션에서 일어난다. MVP 는 실 PG 연동이 아니라 모킹 흐름이지만,
 * 실제 결제창을 붙였을 때 경로가 바뀌지 않도록 요청/승인 2단계를 그대로 둔다.
 *
 * <p>{@code chargeRequestKey} 는 (customer, charge_request_key) 유니크로 재전송을 막는 멱등키다.
 * 클라이언트가 UUID 를 만들어 보내고, 같은 키로 다시 오면 새로 만들지 않고 기존 결과를 돌려준다.
 */
@Schema(description = "포인트 충전 요청")
public record PointChargeRequest(

        @Schema(description = "충전 요청 멱등키(UUID). 재전송 시 같은 값을 그대로 보낸다.",
                example = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34")
        @NotBlank
        @Size(min = 36, max = 36)
        String chargeRequestKey,

        @Schema(description = "충전 금액(포인트). 양수만 허용한다.", example = "10000")
        @Positive
        long amount,

        @Schema(description = "결제 수단", example = "CARD")
        @NotNull
        PaymentMethod paymentMethod
) {
}
