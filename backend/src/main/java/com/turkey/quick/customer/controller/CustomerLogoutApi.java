package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "customer-logout", description = "고객 로그아웃")
@RequestMapping("/api/customer/logout")
public interface CustomerLogoutApi {

    @Operation(
            operationId = "customerLogout",
            summary = "고객 로그아웃",
            description = "서버에 저장된 세션을 삭제하고 세션 쿠키를 만료시킨다. "
                    + "세션 쿠키가 없거나 이미 만료·존재하지 않는 세션이 전달돼도 항상 200으로 로그아웃 완료 처리한다."
    )
    @PostMapping
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response);
}
