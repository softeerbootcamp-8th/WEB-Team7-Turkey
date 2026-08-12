package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlRequest;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    @Operation(operationId = "issueRiderDeliveryProofPhotoUploadUrl", summary = "완료 인증 사진 업로드 URL 발급",
            description = "proofType=PHOTO 로 완료하기 전, 라이더가 사진을 직접 S3 에 올릴 수 있는 "
                    + "presigned PUT URL을 발급한다(#61 후속, 서버가 멀티파트로 직접 받던 방식에서 전환). "
                    + "배정 라이더·상태(DELIVERING) 검증은 완료 API와 동일하다. 발급된 key를 그대로 완료 "
                    + "요청(proofValue)에 실어 보내면, 서버가 그 자리에서 HeadObject 로 실제 업로드 여부와 "
                    + "크기를 재검증한다 — presigned URL 자체는 크기를 강제하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "업로드 URL 발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "지원하지 않는 Content-Type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "배정되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DELIVERING이 아닌 배송")
    })
    ApiResponse<RiderDeliveryProofUploadUrlResponse> issueProofPhotoUploadUrl(
            AuthenticatedRider rider,
            Long deliveryId,
            @Valid RiderDeliveryProofUploadUrlRequest request);

    @Operation(operationId = "completeRiderDelivery", summary = "배송 완료(멀티파트, 서버 직접 업로드 경로)",
            description = "DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE + 정산 생성을 한 트랜잭션으로 처리하고 "
                    + "배송 완료 인증을 남긴다. 기존 프론트가 이미 이 URL로 붙어 있어 경로를 유지한다 "
                    + "(원래 #61 후속 구현). proofType=PHOTO 면 file 을 함께 받아 서버가 그 자리에서 "
                    + "S3에 올린다. presigned URL로 미리 올린 키로 완료하는 신규 경로는 별도 URL "
                    + "{@link #completeDeliveryWithProofKey}다(사람 확인, 두 경로 병행 — presign 쪽 "
                    + "사후검증·고아 객체 정리가 아직 확정되지 않아 부하가 실측되기 전까지 이 경로를 "
                    + "유지한다). 그 외 인증 방식은 file 없이 proofValue 로 참조값을 직접 받는다. "
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

    @Operation(operationId = "completeRiderDeliveryWithProofKey", summary = "배송 완료(JSON, presigned URL 경로)",
            description = "완료 처리 자체는 {@link #completeDelivery}와 동일한 트랜잭션·검증을 공유한다. "
                    + "신규 URL(POST .../complete/proof-key)이다 — 기존 멀티파트 URL을 프론트 연동 "
                    + "때문에 그대로 유지해야 해서 별도로 뺐다. proofType=PHOTO 면 proofValue 는 업로드 "
                    + "URL 발급 API로 미리 받은 저장소 키다 — 서버는 그 키를 HeadObject 로 재검증(존재· "
                    + "크기 상한)하고, 초과하면 객체를 삭제한 뒤 400으로 거부한다(#61 후속, PUT presign "
                    + "전환). 그 외 인증 방식은 proofValue 로 참조값을 직접 받는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "완료 및 정산 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "인증 정보 누락, 소유하지 않은 업로드 키, "
                            + "업로드 미확인, 크기 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "배정되지 않은 라이더"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DELIVERING이 아니거나 이미 완료된 배송")
    })
    ApiResponse<RiderDeliveryCompleteResponse> completeDeliveryWithProofKey(
            AuthenticatedRider rider,
            Long deliveryId,
            @Valid RiderDeliveryCompleteRequest request);
}
