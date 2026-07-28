package com.turkey.quick.customer.dto;

import com.turkey.quick.customer.service.CustomerLoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CustomerLoginResponse(

        @Schema(description = "회원 ID", example = "1")
        Long memberId,

        @Schema(description = "로그인 ID", example = "quick_user01")
        String loginId,

        @Schema(description = "이름", example = "홍길동")
        String name
) {

    public static CustomerLoginResponse from(CustomerLoginResult result) {
        return new CustomerLoginResponse(result.memberId(), result.loginId(), result.name());
    }
}
