package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.PhoneVerificationRequest;
import com.turkey.quick.member.dto.PhoneVerificationResponse;
import com.turkey.quick.member.service.PhoneVerificationResult;
import com.turkey.quick.member.service.PhoneVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "phone-verification", description = "휴대전화 본인 인증")
@RestController
@RequestMapping("/api/phone-verifications")
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationService phoneVerificationService;
    private final Environment environment;

    @Operation(
            summary = "인증번호 요청",
            description = "회원가입 또는 계정 찾기에 쓸 휴대전화 인증번호를 발송한다. "
                    + "SIGNUP 목적은 이미 가입된 번호면 409, 재전송 쿨다운 중이면 429, 발송 실패면 502를 반환한다."
    )
    @PostMapping
    public ApiResponse<PhoneVerificationResponse> request(@Valid @RequestBody PhoneVerificationRequest request) {
        PhoneVerificationResult result = phoneVerificationService.request(request.phoneNumber(), request.purpose());
        boolean includeDebugCode = environment.matchesProfiles("local");
        return ApiResponse.ok(PhoneVerificationResponse.from(result, includeDebugCode));
    }
}
