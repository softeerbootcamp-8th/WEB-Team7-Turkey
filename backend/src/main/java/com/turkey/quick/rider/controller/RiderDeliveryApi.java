package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 라이더 진행 중 배송 API 계약(인터페이스 전용, 구현 없음).
 *
 * <p>진행 중 배송은 라이더당 최대 1건이므로 조회 경로에 식별자를 두지 않는다({@code /current}).
 * 프론트의 `rider/delivery` 도 같은 이유로 고정 경로이며, 새로고침·재로그인 후 이 API 하나로
 * 화면을 복구한다. 반면 전이·완료는 어떤 배송에 대한 행위인지 요청에 못박아야 하므로
 * {@code {deliveryId}} 를 받는다 — 화면이 오래된 배송을 들고 있을 때 엉뚱한 건이 완료되는 것을 막는다.
 *
 * <p>완료는 상태 전이와 분리된 별도 API 다. 배송 DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE +
 * 정산 생성이 한 트랜잭션이고, 인증 자료(delivery_proof)까지 함께 남기기 때문이다.
 */
@Tag(name = "rider-delivery", description = "라이더 진행 중 배송 — 조회·단계 전이·완료")
@RequestMapping("/api/rider/deliveries")
public interface RiderDeliveryApi {

    @Operation(summary = "진행 중 배송 조회",
            description = "라이더가 수행 중인 배송 1건을 조회한다. 진행 중 배송이 없으면 data 가 null 이다.")
    @GetMapping("/current")
    ApiResponse<RiderDeliveryResponse> getCurrentDelivery();

    @Operation(summary = "배송 단계 전이",
            description = "픽업 출발/픽업 완료/배송 출발을 처리한다. 목표 상태가 아니라 행위를 받으며, "
                    + "현재 상태에서 허용되지 않는 전이(단계 건너뛰기, 중복 요청)는 409 로 거부한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "전이 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "현재 상태에서 허용되지 않는 전이")
    })
    @PostMapping("/{deliveryId}/transition")
    ApiResponse<RiderDeliveryResponse> transitionDelivery(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId,

            @Valid @RequestBody RiderDeliveryTransitionRequest request);

    @Operation(summary = "배송 완료",
            description = "DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE + 정산 생성을 한 트랜잭션으로 처리하고 "
                    + "배송 완료 인증을 남긴다. 사진은 업로드 후 참조값(URL/키)만 넘긴다(바이너리 미수신).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "완료 및 정산 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DELIVERING 이 아니거나 이미 완료된 배송")
    })
    @PostMapping("/{deliveryId}/complete")
    ApiResponse<RiderDeliveryCompleteResponse> completeDelivery(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId,

            @Valid @RequestBody RiderDeliveryCompleteRequest request);
}
