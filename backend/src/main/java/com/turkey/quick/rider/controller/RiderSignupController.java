package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderSignupRequest;
import com.turkey.quick.rider.dto.RiderSignupResponse;
import com.turkey.quick.rider.service.RiderSignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "rider-signup", description = "라이더 회원가입")
@RestController
@RequestMapping("/api/rider/signup")
@RequiredArgsConstructor
public class RiderSignupController {

    private final RiderSignupService riderSignupService;

    @Operation(
            summary = "라이더 회원가입",
            description = "휴대전화 인증을 완료한 라이더의 계정과 라이더 프로필(초기 운행 상태 UNAVAILABLE)을 생성한다. "
                    + "로그인 ID·휴대전화 번호가 중복이면 409, 인증 미완료·형식 오류·필수 약관 미동의는 400을 반환한다."
    )
    @PostMapping
    public ApiResponse<RiderSignupResponse> signup(@Valid @RequestBody RiderSignupRequest request) {
        return ApiResponse.ok(RiderSignupResponse.from(riderSignupService.signup(request)));
    }
}
