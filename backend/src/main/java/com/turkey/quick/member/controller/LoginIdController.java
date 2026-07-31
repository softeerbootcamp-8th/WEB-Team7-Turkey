package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.LoginIdAvailabilityResponse;
import com.turkey.quick.member.service.LoginIdAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
public class LoginIdController implements LoginIdApi {

    private final LoginIdAvailabilityService loginIdAvailabilityService;

    @Override
    public ApiResponse<LoginIdAvailabilityResponse> checkAvailability(String loginId) {
        return ApiResponse.ok(LoginIdAvailabilityResponse.from(loginIdAvailabilityService.check(loginId)));
    }
}
