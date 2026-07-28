package com.turkey.quick.member.dto;

import com.turkey.quick.member.service.PhoneVerificationResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record PhoneVerificationResponse(

        @Schema(description = "인증번호 만료 시각(UTC)", example = "2026-07-28T10:05:00")
        LocalDateTime expiresAt,

        @Schema(description = "local 프로파일에서만 채워지는 디버그용 인증번호. 그 외에는 null.", example = "482913")
        String debugCode
) {

    public static PhoneVerificationResponse from(PhoneVerificationResult result, boolean includeDebugCode) {
        return new PhoneVerificationResponse(
                result.expiresAt(),
                includeDebugCode ? result.code() : null
        );
    }
}
