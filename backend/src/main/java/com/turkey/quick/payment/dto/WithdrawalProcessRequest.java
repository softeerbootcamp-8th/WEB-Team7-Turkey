package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 출금 모의 처리 요청(#90). {@code PointChargeConfirmRequest} 와 같은 이유로 필드가 하나다 — 이체
 * 성공·실패는 <b>은행(모의 게이트웨이)의 판단</b>이라 호출자가 "성공/실패"를 직접 통보하지 않는다.
 *
 * <p>{@code authToken} 은 모의 이체 결과를 통제하는 값이다. {@code MockPayoutGateway.DECLINE_TOKEN}
 * 을 보내면 거절 경로를 확인할 수 있다 — 결제의 모의 결제 화면과 같은 자리이지만, 이체는 클라이언트가
 * 결제창을 거쳐 이 값을 들고 오는 왕복이 없으므로 모의 처리 도구가 직접 채워 보낸다.
 */
@Schema(description = "출금 모의 처리 요청")
public record WithdrawalProcessRequest(

        @Schema(description = "모의 이체 결과를 통제하는 토큰. MockPayoutGateway.DECLINE_TOKEN 이면 거절.",
                example = "mock_auth_9f2c41")
        @NotBlank
        @Size(max = 200)
        String authToken
) {
}
