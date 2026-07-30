package com.turkey.quick.customer.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 로그아웃(이슈 #28). 세션 삭제가 SessionStore 호출 한 줄이라 별도 서비스 계층 없이
 * 컨트롤러에서 바로 처리한다.
 */
@RestController
@RequiredArgsConstructor
public class CustomerLogoutController implements CustomerLogoutApi {

    private final SessionStore sessionStore;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = SessionCookie.extractSessionId(request);
        if (sessionId != null) {
            sessionStore.delete(sessionId);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.expired(cookieSecure).toString());
        return ApiResponse.ok(null);
    }
}
