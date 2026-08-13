package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.dto.RiderOperatingStatusUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 라이더 운행 상태 API 계약.
 *
 * <p><b>역할 분담</b>은 payment 의 {@code CustomerPointApi}·{@code RiderPointApi} 와 같다(Discussion
 * #245): 이 인터페이스에는 문서화 어노테이션과 호출자 계약인 Bean Validation을 두고, 경로·HTTP 메서드
 * 매핑과 바인딩은 구현체에 둔다. 오버라이드 구현은 파라미터 제약을 추가하거나 중복 선언하지 않는다.
 * 빈으로 등록된 구현체가 있어야 springdoc 이 스캔한다.
 *
 * <p>운행 상태는 라이더 화면 분기(홈/콜 목록/진행 배송)와 위치 전송 주기를 결정하는 값이라, 앱
 * 진입·새로고침 직후 가장 먼저 조회한다.
 *
 * <p>조회(GET)는 #53 에서 구현했다. 변경(PATCH, {@code changeOperatingStatus})은 #54 범위라 구현체가
 * 아직 {@code return null} 스텁이다. BUSY 로의 전이는 이 API 에 없다 — 배차 확정과 배송 완료의 부수
 * 효과로만 일어난다.
 */
@Tag(name = "rider-operating-status", description = "라이더 운행 상태 — 조회 및 콜 받기/운행 종료")
public interface RiderOperatingStatusApi {

    @Operation(operationId = "getRiderOperatingStatus", summary = "운행 상태 조회",
            description = "현재 운행 상태와 진행 중 배송 식별자를 조회한다. BUSY 면 진행 배송 화면으로 복귀한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료")
    })
    ApiResponse<RiderOperatingStatusResponse> getOperatingStatus(AuthenticatedRider rider);

    @Operation(operationId = "changeRiderOperatingStatus", summary = "운행 상태 변경",
            description = "콜 받기(GO_ONLINE) / 운행 종료(GO_OFFLINE). 목표 상태가 아니라 행위를 받는다. "
                    + "배송 수행 중(BUSY)에는 직접 변경할 수 없고, 이미 같은 상태면 오류 없이 현재 상태를 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "변경 성공 또는 이미 같은 상태(멱등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "배송 수행 중(BUSY)이라 직접 변경할 수 없음")
    })
    ApiResponse<RiderOperatingStatusResponse> changeOperatingStatus(
            AuthenticatedRider rider, @Valid RiderOperatingStatusUpdateRequest request);
}
