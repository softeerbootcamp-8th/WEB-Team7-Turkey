package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "rider-session", description = "라이더 로그인 상태 확인")
@RestController
@RequestMapping("/api/rider/session")
public class RiderSessionController {

    @Operation(
            operationId = "getRiderSession",
            summary = "라이더 로그인 상태 확인",
            description = "SESSION_ID 쿠키로 현재 세션을 검증하고 인증된 라이더 정보와 현재 운행 상태를 반환한다. "
                    + "쿠키 없음, 세션 없음(만료 포함), 역할 불일치, 비활성 계정은 모두 동일한 401을 반환한다."
    )
    @GetMapping
    public ApiResponse<RiderSessionResponse> session(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider) {
        return ApiResponse.ok(RiderSessionResponse.from(rider));
    }
}
