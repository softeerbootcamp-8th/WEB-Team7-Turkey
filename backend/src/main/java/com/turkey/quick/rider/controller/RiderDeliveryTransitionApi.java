package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** #58에서 실제로 노출하는 라이더 배송 단계 전이 계약. */
@Tag(name = "rider-delivery", description = "라이더 진행 중 배송 — 조회·단계 전이·완료")
public interface RiderDeliveryTransitionApi {

    @Operation(operationId = "startRiderMovingToPickup", summary = "픽업지 이동 시작",
            description = "배정된 라이더가 픽업지로 출발한다. ASSIGNED가 아니거나 중복 요청이면 409로 거부한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "전이 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "배정되지 않은 라이더 또는 BUSY가 아닌 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "ASSIGNED가 아니거나 중복 요청")
    })
    ApiResponse<RiderDeliveryResponse> transitionDelivery(
            AuthenticatedRider rider,
            @Parameter(description = "배송요청 식별자", example = "1024") Long deliveryId,
            RiderDeliveryTransitionRequest request);
}
