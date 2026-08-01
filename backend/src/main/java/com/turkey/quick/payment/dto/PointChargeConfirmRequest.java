package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 포인트 충전 승인 요청(#33).
 *
 * <p>필드가 둘뿐인 것은 실 PG 승인 요청과 모양을 맞춘 것이다. 승인 <b>성공·실패는 PG 의 판단</b>이라
 * 클라이언트가 요청으로 통보하지 않는다 — 클라이언트가 주는 것은 결제창을 통과해 받아 온 불투명한
 * 토큰과, 서버가 대조할 금액뿐이다.
 *
 * <p><b>{@code amount} 는 검증 전용이다.</b> 승인 금액은 항상 PENDING 행의 requested_amount 전액이고
 * (부분 결제 없음, {@code ck_point_charge_state_values} 가 강제), 이 값은 서버가 기억하는 요청 금액과
 * 대조해 다르면 거부하는 데에만 쓴다(이슈 처리흐름 ④). 전달받은 값을 승인 금액으로 쓰지 않으므로
 * 금액을 바꿔 보내도 더 많은 포인트를 받을 통로가 되지 않는다.
 *
 * <p>{@code authToken} 은 벤더 중립 이름이다. 토스페이먼츠는 successUrl 리다이렉트로 {@code paymentKey}
 * 를, 카카오페이는 {@code pg_token} 을 내려주는데 우리 계약은 그 이름에 묶이지 않는다.
 * MVP 모의 결제에서는 화면이 만들어 보내며, {@code MockPaymentGateway.DECLINE_TOKEN} 을 보내면
 * 거절 경로를 확인할 수 있다.
 */
@Schema(description = "포인트 충전 승인 요청")
public record PointChargeConfirmRequest(

        @Schema(description = "결제 금액. 서버가 기억하는 요청 금액과 대조하는 데만 쓴다.",
                example = "10000")
        @Positive
        long amount,

        @Schema(description = "결제창을 통과해 받아 온 인증 토큰(토스 paymentKey · 카카오 pg_token 등).",
                example = "mock_auth_9f2c41")
        @NotBlank
        @Size(max = 200)
        String authToken
) {
}
