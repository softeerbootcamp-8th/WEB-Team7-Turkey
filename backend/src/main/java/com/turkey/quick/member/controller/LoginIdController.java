package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.LoginIdAvailabilityResponse;
import com.turkey.quick.member.service.LoginIdAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "login-id", description = "로그인 ID 중복 확인")
@RestController
@RequestMapping("/api/login-ids")
@RequiredArgsConstructor
@Validated
public class LoginIdController {

    private final LoginIdAvailabilityService loginIdAvailabilityService;

    @Operation(
            operationId = "checkLoginIdAvailability",
            summary = "아이디 중복 확인",
            description = "고객·라이더를 포함한 전체 회원 기준으로 로그인 ID 사용 가능 여부를 확인한다. 형식(길이·문자) 제약은 없다."
    )
    @GetMapping("/availability")
    public ApiResponse<LoginIdAvailabilityResponse> checkAvailability(
            @RequestParam @NotBlank(message = "로그인 ID는 필수입니다.") String loginId) {
        return ApiResponse.ok(LoginIdAvailabilityResponse.from(loginIdAvailabilityService.check(loginId)));
    }
}
