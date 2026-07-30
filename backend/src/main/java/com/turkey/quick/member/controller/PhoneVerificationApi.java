package com.turkey.quick.member.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.dto.PhoneVerificationConfirmRequest;
import com.turkey.quick.member.dto.PhoneVerificationConfirmResponse;
import com.turkey.quick.member.dto.PhoneVerificationRequest;
import com.turkey.quick.member.dto.PhoneVerificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "phone-verification", description = "휴대전화 본인 인증")
@RequestMapping("/api/phone-verifications")
public interface PhoneVerificationApi {

    @Operation(
            operationId = "requestPhoneVerification",
            summary = "인증번호 요청",
            description = "회원가입 또는 계정 찾기에 쓸 휴대전화 인증번호를 발송한다. "
                    + "SIGNUP 목적은 이미 가입된 번호면 409, 재전송 쿨다운 중이면 429, 발송 실패면 502를 반환한다."
    )
    @PostMapping
    ApiResponse<PhoneVerificationResponse> request(@Valid @RequestBody PhoneVerificationRequest request);

    @Operation(
            operationId = "confirmPhoneVerification",
            summary = "인증번호 확인",
            description = "발급된 휴대전화 인증번호를 검증하고 일회성 인증 완료 토큰을 발급한다. "
                    + "인증 요청 이력이 없거나 만료됐으면 404, 불일치하면 400, 시도 횟수(5회)를 초과하면 429를 반환한다."
    )
    @PostMapping("/confirm")
    ApiResponse<PhoneVerificationConfirmResponse> confirm(@Valid @RequestBody PhoneVerificationConfirmRequest request);
}
