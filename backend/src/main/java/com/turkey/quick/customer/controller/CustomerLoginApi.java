package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerLoginRequest;
import com.turkey.quick.customer.dto.CustomerLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "customer-login", description = "고객 로그인")
@RequestMapping("/api/customer/login")
public interface CustomerLoginApi {

    @Operation(
            operationId = "customerLogin",
            summary = "고객 로그인",
            description = "로그인 ID·비밀번호로 인증하고 서버 세션을 생성해 HttpOnly 쿠키로 발급한다. "
                    + "아이디 없음, 라이더 계정, 비밀번호 불일치, 비활성 계정은 모두 동일한 401을 반환한다(계정 존재 여부 비노출)."
    )
    @PostMapping
    ApiResponse<CustomerLoginResponse> login(@Valid @RequestBody CustomerLoginRequest request, HttpServletResponse response);
}
