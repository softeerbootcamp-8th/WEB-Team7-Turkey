package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

/**
 * 라이더 진행 중 배송 전체 API 계약.
 *
 * <p>단계 전이는 #58·#59·#65에서, 배송 완료는 #62에서 구현한다.
 * 진행 배송 조회는 #86에서 구현한다.
 */
@Tag(name = "rider-delivery", description = "라이더 진행 중 배송 — 조회·단계 전이·완료")
public interface RiderDeliveryApi extends RiderDeliveryTransitionApi {

    @Operation(operationId = "getCurrentRiderDelivery", summary = "진행 중 배송 조회",
            description = "라이더가 수행 중인 배송 1건을 조회한다. 진행 중 배송이 없으면 data가 null이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "라이더 상태와 진행 배송 정보 불일치")
    })
    ApiResponse<RiderDeliveryResponse> getCurrentDelivery(AuthenticatedRider rider);

    @Operation(operationId = "completeRiderDelivery", summary = "배송 완료",
            description = "DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE + 정산 생성을 한 트랜잭션으로 처리하고 "
                    + "배송 완료 인증을 남긴다. proofType=PHOTO 면 file 을 받아 서버가 직접 S3 에 올린다 "
                    + "— 의도적으로 가장 단순하게 구현했다(#61 후속, "
                    + "docs/worklog/2026-08-04-61-delivery-completion-proof.md 참고). 그 외 인증 방식은 "
                    + "file 없이 proofValue 로 참조값을 직접 받는다. "
                    + "완료 인증 등록(RIDE-QUICK-008, #61)을 별도 API로 분리하지 않고 이 완료 트랜잭션 안에 "
                    + "통합했다 — 배정 라이더·상태 검증, 인증 형식 검증, 중복 등록 차단을 이 한 요청이 전부 "
                    + "수행한다(사람 확인, #61 검토).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "완료 및 정산 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "인증 정보 누락(file·proofValue 둘 다 없음)"),
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
            Long deliveryId,
            ProofType proofType,
            MultipartFile file,
            String proofValue);
}
