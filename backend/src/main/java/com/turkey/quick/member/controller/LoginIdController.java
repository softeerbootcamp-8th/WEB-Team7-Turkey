package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.LoginIdAvailabilityResponse;
import com.turkey.quick.member.service.LoginIdAvailabilityService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/login-ids")
public class LoginIdController implements LoginIdApi {

    private final LoginIdAvailabilityService loginIdAvailabilityService;

    @Override
    @GetMapping("/availability")
    public ApiResponse<LoginIdAvailabilityResponse> checkAvailability(
            @RequestParam @NotBlank(message = "로그인 ID는 필수입니다.") String loginId) {
        return ApiResponse.ok(LoginIdAvailabilityResponse.from(loginIdAvailabilityService.check(loginId)));
    }
}
