package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "rider-session", description = "라이더 로그인 상태 확인")
public interface RiderSessionApi {

    @Operation(
            operationId = "getRiderSession",
            summary = "라이더 로그인 상태 확인",
            description = "SESSION_ID 쿠키로 현재 세션을 검증하고 인증된 라이더 정보와 현재 운행 상태를 반환한다. "
                    + "쿠키 없음, 세션 없음(만료 포함), 역할 불일치, 비활성 계정은 모두 동일한 401을 반환한다."
    )
    ApiResponse<RiderSessionResponse> session(AuthenticatedRider rider);
}
