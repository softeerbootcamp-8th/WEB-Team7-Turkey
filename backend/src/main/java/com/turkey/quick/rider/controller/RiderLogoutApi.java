package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 라이더 로그아웃 API 계약(이슈 #51).
 *
 * <p>세션 삭제·쿠키 만료는 고객 로그아웃(#28)과 동일하게 컨트롤러가 담당하고, 라이더에만 있는
 * 운행 상태 처리(BUSY 거부, AVAILABLE→UNAVAILABLE)만 서비스로 위임한다.
 *
 * <p>인증을 인터셉터에 맡기지 않는다: 이미 만료·삭제된 세션에도 로그아웃 완료(200)를 줘야 하는데
 * {@code RiderSessionInterceptor}는 그 경우 401을 던진다. 그래서 이 경로는 {@code RiderWebMvcConfig}의
 * addPathPatterns에 등록하지 않고 컨트롤러가 쿠키를 직접 읽는다(#28과 동일한 판단).
 */
@Tag(name = "rider-logout", description = "라이더 로그아웃")
public interface RiderLogoutApi {

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
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response);
}
