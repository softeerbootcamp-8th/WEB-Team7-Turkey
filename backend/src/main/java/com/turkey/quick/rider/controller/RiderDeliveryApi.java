package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 라이더 진행 중 배송 전체 API 계약.
 *
 * <p>단계 전이는 #58에서 구현하고, 진행 배송 조회·완료는 각각의 후속 이슈에서 구현한다.
 * 아직 구현되지 않은 메서드는 문서 계약으로만 유지하며 실제 라우트로 노출하지 않는다.
 */
@Tag(name = "rider-delivery", description = "라이더 진행 중 배송 — 조회·단계 전이·완료")
public interface RiderDeliveryApi extends RiderDeliveryTransitionApi {

    @Operation(operationId = "getCurrentRiderDelivery", summary = "진행 중 배송 조회",
            description = "라이더가 수행 중인 배송 1건을 조회한다. 진행 중 배송이 없으면 data가 null이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더")
    })
    ApiResponse<RiderDeliveryResponse> getCurrentDelivery(AuthenticatedRider rider);

    @Operation(operationId = "completeRiderDelivery", summary = "배송 완료",
            description = "DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE + 정산 생성을 한 트랜잭션으로 처리하고 "
                    + "배송 완료 인증을 남긴다. 사진은 업로드 후 참조값(URL/키)만 넘긴다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "완료 및 정산 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "배정되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DELIVERING이 아니거나 이미 완료된 배송")
    })
    ApiResponse<RiderDeliveryCompleteResponse> completeDelivery(
            AuthenticatedRider rider,
            @Parameter(description = "배송요청 식별자", example = "1024") Long deliveryId,
            RiderDeliveryCompleteRequest request);
}
