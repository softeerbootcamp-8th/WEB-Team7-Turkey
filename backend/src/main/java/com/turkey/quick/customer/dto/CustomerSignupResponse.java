package com.turkey.quick.customer.dto;

import com.turkey.quick.customer.service.CustomerSignupResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record CustomerSignupResponse(

        @Schema(description = "생성된 회원 ID", example = "1")
        Long memberId,

        @Schema(description = "로그인 ID", example = "quick_user01")
        String loginId,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "가입 시각(UTC)")
        LocalDateTime createdAt
) {

    public static CustomerSignupResponse from(CustomerSignupResult result) {
        return new CustomerSignupResponse(result.memberId(), result.loginId(), result.name(), result.createdAt());
    }
}
