package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.LoginIdAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;

@Tag(name = "login-id", description = "로그인 ID 중복 확인")
public interface LoginIdApi {

    @Operation(
            operationId = "checkLoginIdAvailability",
            summary = "아이디 중복 확인",
            description = "고객·라이더를 포함한 전체 회원 기준으로 로그인 ID 사용 가능 여부를 확인한다. 형식(길이·문자) 제약은 없다."
    )
    ApiResponse<LoginIdAvailabilityResponse> checkAvailability(
            @NotBlank(message = "로그인 ID는 필수입니다.") String loginId);
}
