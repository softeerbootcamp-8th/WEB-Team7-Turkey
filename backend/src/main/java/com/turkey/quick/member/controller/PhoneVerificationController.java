package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.PhoneVerificationConfirmRequest;
import com.turkey.quick.member.dto.PhoneVerificationConfirmResponse;
import com.turkey.quick.member.dto.PhoneVerificationRequest;
import com.turkey.quick.member.dto.PhoneVerificationResponse;
import com.turkey.quick.member.service.PhoneVerificationConfirmResult;
import com.turkey.quick.member.service.PhoneVerificationResult;
import com.turkey.quick.member.service.PhoneVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PhoneVerificationController implements PhoneVerificationApi {

    private final PhoneVerificationService phoneVerificationService;
    private final Environment environment;

    @Override
    public ApiResponse<PhoneVerificationResponse> request(PhoneVerificationRequest request) {
        PhoneVerificationResult result = phoneVerificationService.request(request.phoneNumber(), request.purpose());
        boolean includeDebugCode = environment.matchesProfiles("local");
        return ApiResponse.ok(PhoneVerificationResponse.from(result, includeDebugCode));
    }

    @Override
    public ApiResponse<PhoneVerificationConfirmResponse> confirm(PhoneVerificationConfirmRequest request) {
        PhoneVerificationConfirmResult result = phoneVerificationService.confirm(
                request.phoneNumber(), request.purpose(), request.code());
        return ApiResponse.ok(PhoneVerificationConfirmResponse.from(result));
    }
}
