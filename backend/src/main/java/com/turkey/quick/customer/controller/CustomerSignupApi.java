package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.customer.dto.CustomerSignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "customer-signup", description = "고객 회원가입")
public interface CustomerSignupApi {

    @Operation(
            operationId = "customerSignup",
            summary = "고객 회원가입",
            description = "휴대전화 인증을 완료한 고객의 계정을 생성한다. "
                    + "로그인 ID·휴대전화 번호가 중복이면 409, 인증 미완료·형식 오류·필수 약관 미동의는 400을 반환한다."
    )
    ApiResponse<CustomerSignupResponse> signup(CustomerSignupRequest request);
}
