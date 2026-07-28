package com.turkey.quick.member.dto;

import com.turkey.quick.member.service.LoginIdAvailabilityResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginIdAvailabilityResponse(

        @Schema(description = "사용 가능 여부", example = "true")
        boolean available,

        @Schema(description = "사용 불가 사유. 사용 가능하면 null.", example = "이미 사용 중인 아이디입니다.")
        String reason
) {

    public static LoginIdAvailabilityResponse from(LoginIdAvailabilityResult result) {
        return new LoginIdAvailabilityResponse(result.available(), result.reason());
    }
}
