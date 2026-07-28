package com.turkey.quick.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CustomerLoginRequest(

        @Schema(description = "로그인 ID", example = "quick_user01")
        @NotBlank(message = "로그인 ID는 필수입니다.")
        String loginId,

        @Schema(description = "비밀번호", example = "p@ssw0rd")
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
