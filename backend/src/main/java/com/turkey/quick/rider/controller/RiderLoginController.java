package com.turkey.quick.rider.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderLoginRequest;
import com.turkey.quick.rider.dto.RiderLoginResponse;
import com.turkey.quick.rider.service.RiderLoginResult;
import com.turkey.quick.rider.service.RiderLoginService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/login")
public class RiderLoginController implements RiderLoginApi {

    private final RiderLoginService riderLoginService;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    @PostMapping
    public ApiResponse<RiderLoginResponse> login(
            @Valid @RequestBody RiderLoginRequest request,
            HttpServletResponse response) {
        RiderLoginResult result = riderLoginService.login(request.loginId(), request.password());

        var cookie = SessionCookie.of(result.sessionId(), result.sessionTtl(), cookieSecure);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ApiResponse.ok(RiderLoginResponse.from(result));
    }
}
