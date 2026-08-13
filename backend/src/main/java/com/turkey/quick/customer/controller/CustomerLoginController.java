package com.turkey.quick.customer.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerLoginRequest;
import com.turkey.quick.customer.dto.CustomerLoginResponse;
import com.turkey.quick.customer.service.CustomerLoginResult;
import com.turkey.quick.customer.service.CustomerLoginService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/login")
public class CustomerLoginController implements CustomerLoginApi {

    private final CustomerLoginService customerLoginService;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    @PostMapping
    public ApiResponse<CustomerLoginResponse> login(
            @RequestBody CustomerLoginRequest request,
            HttpServletResponse response) {
        CustomerLoginResult result = customerLoginService.login(request.loginId(), request.password());

        var cookie = SessionCookie.of(result.sessionId(), cookieSecure);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ApiResponse.ok(CustomerLoginResponse.from(result));
    }
}
