package com.turkey.quick.rider.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.service.RiderLogoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link RiderLogoutApi} 구현. 세션 조회·삭제·쿠키 만료를 담당하고, 운행 상태 전이는
 * {@link RiderLogoutService}로 위임한다.
 *
 * <p>세션 삭제가 상태 전이 커밋 뒤에 오도록 순서를 잡는다: 상태 변경 서비스가 정상 리턴하면
 * (=커밋 완료) 그 뒤에 세션을 지운다. BUSY면 서비스가 409를 던져 세션 삭제·쿠키 만료에 도달하지
 * 않으므로 라이더는 로그인 상태로 남는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/logout")
public class RiderLogoutController implements RiderLogoutApi {

    private final SessionStore sessionStore;
    private final RiderLogoutService riderLogoutService;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    @PostMapping
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = SessionCookie.extractSessionId(request);
        if (sessionId != null) {
            // 세션이 유효하면 운행 상태를 먼저 정리한다. BUSY면 여기서 409가 던져져
            // 아래 세션 삭제·쿠키 만료에 도달하지 않는다 — 라이더는 로그인 상태로 남는다.
            sessionStore.findMemberId(sessionId).ifPresent(riderLogoutService::changeStatusForLogout);
            sessionStore.delete(sessionId); // 상태 전이 커밋 이후에 삭제된다
        }

        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.expired(cookieSecure).toString());
        return ApiResponse.ok(null);
    }
}
