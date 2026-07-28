package com.turkey.quick.rider.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderLoginRequest;
import com.turkey.quick.rider.dto.RiderLoginResponse;
import com.turkey.quick.rider.service.RiderLoginResult;
import com.turkey.quick.rider.service.RiderLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "rider-login", description = "라이더 로그인")
@RestController
@RequestMapping("/api/rider/login")
@RequiredArgsConstructor
public class RiderLoginController {

    private final RiderLoginService riderLoginService;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Operation(
            operationId = "riderLogin",
            summary = "라이더 로그인",
            description = "로그인 ID·비밀번호로 인증하고 서버 세션을 생성해 HttpOnly 쿠키로 발급한다. "
                    + "아이디 없음, 고객 계정, 비밀번호 불일치, 비활성 계정은 모두 동일한 401을 반환한다(계정 존재 여부 비노출)."
    )
    @PostMapping
    public ApiResponse<RiderLoginResponse> login(
            @Valid @RequestBody RiderLoginRequest request, HttpServletResponse response) {
        RiderLoginResult result = riderLoginService.login(request.loginId(), request.password());

        var cookie = SessionCookie.of(result.sessionId(), result.sessionTtl(), cookieSecure);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ApiResponse.ok(RiderLoginResponse.from(result));
    }
}
