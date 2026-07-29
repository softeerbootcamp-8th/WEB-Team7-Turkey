package com.turkey.quick.rider.controller;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.service.RiderLogoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라이더 로그아웃(이슈 #51). 세션 삭제·쿠키 만료는 고객 로그아웃(#28)과 동일하게 컨트롤러가 담당한다.
 * 라이더만 다른 점은 운행 상태 처리(BUSY 거부, AVAILABLE→UNAVAILABLE)인데, 그 도메인 판단만
 * {@link RiderLogoutService}로 위임한다.
 *
 * <p>세션 삭제가 상태 전이 커밋 뒤에 오도록 순서를 잡는다: 상태 변경 서비스가 정상 리턴하면
 * (=커밋 완료) 그 뒤에 세션을 지운다. BUSY면 서비스가 409를 던져 세션 삭제·쿠키 만료에 도달하지
 * 않으므로 라이더는 로그인 상태로 남는다.
 *
 * <p>인증을 인터셉터에 맡기지 않는 이유: 이미 만료·삭제된 세션에도 로그아웃 완료(200)를 줘야 하는데
 * {@code RiderSessionInterceptor}는 그 경우 401을 던진다. 그래서 이 경로는 {@code RiderWebMvcConfig}의
 * addPathPatterns에 등록하지 않고 컨트롤러가 쿠키를 직접 읽는다(#28과 동일한 판단).
 */
@Tag(name = "rider-logout", description = "라이더 로그아웃")
@RestController
@RequestMapping("/api/rider/logout")
@RequiredArgsConstructor
public class RiderLogoutController {

    private final SessionStore sessionStore;
    private final RiderLogoutService riderLogoutService;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Operation(
            operationId = "riderLogout",
            summary = "라이더 로그아웃",
            description = "서버 세션을 삭제하고 세션 쿠키를 만료시킨다. AVAILABLE 상태면 UNAVAILABLE로 바꿔 배차 후보에서 제외한다. "
                    + "세션 쿠키가 없거나 이미 만료·존재하지 않는 세션이 전달돼도 200으로 로그아웃 완료 처리한다. "
                    + "단, BUSY(배송 수행 중) 상태면 배송 완료 전까지 로그아웃을 거부한다(409)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 완료(세션 없음·만료 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "BUSY 상태(배송 수행 중)라 로그아웃 거부")
    })
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
