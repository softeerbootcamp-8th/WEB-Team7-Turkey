package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 배송 완료 요청.
 *
 * proofType=PHOTO 면 proofValue 는 라이더가 사전에 발급받아(presigned URL 업로드 API) 직접
 * S3 로 올린 사진의 저장소 키다 — 서버는 이 키를 HeadObject 로 재검증(존재·크기)한다(#61 후속,
 * PUT presign + 사후검증 방식으로 전환). 그 외(RECIPIENT_CONFIRMATION, AUTH_CODE)는 proofValue 를
 * 참조값으로 그대로 쓴다. 완료 요청 하나로 인증 등록과 상태 전이를 함께 끝낸다.
 */
@Schema(description = "배송 완료 요청(인증 참조값)")
public record RiderDeliveryCompleteRequest(

        @Schema(description = "인증 방식")
        @NotNull
        ProofType proofType,

        @Schema(description = "인증 참조값. PHOTO는 업로드 URL 발급 시 받은 저장소 키, "
                + "그 외는 수령인 확인 참조·인증코드 등.", example = "1234")
        @Size(max = 500)
        String proofValue
) {
}
