package com.turkey.quick.rider.dto;

import com.turkey.quick.rider.service.RiderSignupResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record RiderSignupResponse(

        @Schema(description = "생성된 회원 ID", example = "1")
        Long memberId,

        @Schema(description = "로그인 ID", example = "quick_rider01")
        String loginId,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "가입 시각(UTC)")
        LocalDateTime createdAt
) {

    public static RiderSignupResponse from(RiderSignupResult result) {
        return new RiderSignupResponse(result.memberId(), result.loginId(), result.name(), result.createdAt());
    }
}
