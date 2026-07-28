package com.turkey.quick.member.dto;

import com.turkey.quick.member.service.PhoneVerificationConfirmResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record PhoneVerificationConfirmResponse(

        @Schema(description = "일회성 인증 완료 토큰. 회원가입/계정찾기 요청에 그대로 실어 보낸다.",
                example = "kQ2m9F1x...")
        String verificationToken,

        @Schema(description = "토큰 만료 시각(UTC)", example = "2026-07-28T10:15:00")
        LocalDateTime expiresAt
) {

    public static PhoneVerificationConfirmResponse from(PhoneVerificationConfirmResult result) {
        return new PhoneVerificationConfirmResponse(result.verificationToken(), result.expiresAt());
    }
}
