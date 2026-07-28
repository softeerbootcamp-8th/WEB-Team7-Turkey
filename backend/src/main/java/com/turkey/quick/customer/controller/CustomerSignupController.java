package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.customer.dto.CustomerSignupResponse;
import com.turkey.quick.customer.service.CustomerSignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "customer-signup", description = "고객 회원가입")
@RestController
@RequestMapping("/api/customer/signup")
@RequiredArgsConstructor
public class CustomerSignupController {

    private final CustomerSignupService customerSignupService;

    @Operation(
            summary = "고객 회원가입",
            description = "휴대전화 인증을 완료한 고객의 계정을 생성한다. "
                    + "로그인 ID·휴대전화 번호가 중복이면 409, 인증 미완료·형식 오류·필수 약관 미동의는 400을 반환한다."
    )
    @PostMapping
    public ApiResponse<CustomerSignupResponse> signup(@Valid @RequestBody CustomerSignupRequest request) {
        return ApiResponse.ok(CustomerSignupResponse.from(customerSignupService.signup(request)));
    }
}
