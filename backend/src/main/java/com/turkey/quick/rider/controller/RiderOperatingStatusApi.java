package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.dto.RiderOperatingStatusUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 라이더 운행 상태 API 계약(인터페이스 전용, 구현 없음).
 *
 * <p>운행 상태는 라이더 화면 분기(홈/콜 목록/진행 배송)와 위치 전송 주기를 결정하는 값이라,
 * 앱 진입·새로고침 직후 가장 먼저 조회한다.
 *
 * <p>BUSY 로의 전이는 이 API 에 없다 — 배차 확정과 배송 완료의 부수 효과로만 일어나며,
 * 각각 {@link RiderDeliveryRequestApi}, {@link RiderDeliveryApi} 의 트랜잭션에 묶여 있다.
 */
@Tag(name = "rider-operating-status", description = "라이더 운행 상태 — 조회 및 콜 받기/운행 종료")
@RequestMapping("/api/rider/operating-status")
public interface RiderOperatingStatusApi {

    @Operation(summary = "운행 상태 조회",
            description = "현재 운행 상태와 진행 중 배송 식별자를 조회한다. BUSY 면 진행 배송 화면으로 복귀한다.")
    @GetMapping
    ApiResponse<RiderOperatingStatusResponse> getOperatingStatus();

    @Operation(summary = "운행 상태 변경",
            description = "콜 받기(GO_ONLINE) / 운행 종료(GO_OFFLINE). 목표 상태가 아니라 행위를 받는다. "
                    + "배송 수행 중(BUSY)에는 종료할 수 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "현재 상태에서 허용되지 않는 전이")
    })
    @PatchMapping
    ApiResponse<RiderOperatingStatusResponse> changeOperatingStatus(
            @Valid @RequestBody RiderOperatingStatusUpdateRequest request);
}
