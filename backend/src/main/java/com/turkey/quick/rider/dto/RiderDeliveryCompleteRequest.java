package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * 배송 완료 요청.
 *
 * proofType=PHOTO 면 file 을 받아 서버가 직접 S3 로 올린다(#61 후속, 의도적으로 가장 단순하게
 * 구현 — docs/worklog/2026-08-04-61-delivery-completion-proof.md 참고). 그 외
 * (RECIPIENT_CONFIRMATION, AUTH_CODE)는 file 없이 proofValue 로 참조값을 직접 받는다.
 * 완료 요청 하나로 인증 등록과 상태 전이를 함께 끝낸다 — 별도 업로드 API로 나누지 않는다.
 */
@Schema(description = "배송 완료 요청(사진 파일 또는 인증 참조값)")
public record RiderDeliveryCompleteRequest(

        @Schema(description = "인증 방식")
        @NotNull
        ProofType proofType,

        @Schema(description = "인증 사진 파일. proofType=PHOTO 일 때 이것으로 인증한다.")
        MultipartFile file,

        @Schema(description = "인증 참조값(수령인 확인 참조, 인증코드 등). file 이 없을 때 이 값을 쓴다.",
                example = "1234")
        @Size(max = 500)
        String proofValue
) {
}
