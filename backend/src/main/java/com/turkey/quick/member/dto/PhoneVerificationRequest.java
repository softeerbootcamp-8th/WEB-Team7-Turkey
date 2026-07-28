package com.turkey.quick.member.dto;

import com.turkey.quick.member.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PhoneVerificationRequest(

        @Schema(description = "휴대전화 번호(하이픈 선택)", example = "010-1234-5678")
        @Pattern(regexp = "^01(?:0|1|[6-9])-?\\d{3,4}-?\\d{4}$", message = "휴대전화 번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @Schema(description = "인증 목적", example = "SIGNUP")
        @NotNull(message = "인증 목적은 필수입니다.")
        VerificationPurpose purpose
) {
}
