package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.customer.dto.CustomerSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "customer-session", description = "고객 로그인 상태 확인")
@RestController
@RequestMapping("/api/customer/session")
public class CustomerSessionController {

    @Operation(
            operationId = "getCustomerSession",
            summary = "고객 로그인 상태 확인",
            description = "SESSION_ID 쿠키로 현재 세션을 검증하고 인증된 고객 정보를 반환한다. "
                    + "쿠키 없음, 세션 없음(만료 포함), 역할 불일치, 비활성 계정은 모두 동일한 401을 반환한다."
    )
    @GetMapping
    public ApiResponse<CustomerSessionResponse> session(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE) AuthenticatedCustomer customer) {
        return ApiResponse.ok(CustomerSessionResponse.from(customer));
    }
}
