package com.turkey.quick.rider.dto;

import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record RiderSessionResponse(

        @Schema(description = "회원 ID", example = "1")
        Long memberId,

        @Schema(description = "로그인 ID", example = "quick_rider01")
        String loginId,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "현재 운행 상태")
        OperatingStatus operatingStatus
) {

    public static RiderSessionResponse from(AuthenticatedRider rider) {
        return new RiderSessionResponse(rider.memberId(), rider.loginId(), rider.name(), rider.operatingStatus());
    }
}
