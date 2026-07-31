package com.turkey.quick.member.dto;

import com.turkey.quick.member.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PhoneVerificationConfirmRequest(

        @Schema(description = "휴대전화 번호(하이픈 선택)", example = "010-1234-5678")
        @NotBlank(message = "휴대전화 번호는 필수입니다.")
        @Pattern(regexp = "^01(?:0|1|[6-9])-?\\d{3,4}-?\\d{4}$", message = "휴대전화 번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @Schema(description = "인증 목적", example = "SIGNUP")
        @NotNull(message = "인증 목적은 필수입니다.")
        VerificationPurpose purpose,

        @Schema(description = "6자리 인증번호", example = "482913")
        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다.")
        String code
) {
}
