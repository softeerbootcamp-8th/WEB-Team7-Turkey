package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * 배송 완료 요청(멀티파트, 서버 직접 수신 경로).
 *
 * proofType=PHOTO 면 file 을 받아 서버가 그 자리에서 S3 로 올린다(원래 #61 후속 구현). presigned
 * URL 경로({@link RiderDeliveryCompleteRequest})와 함께 유지한다 — presign 쪽 예외 상황(사후검증,
 * 고아 객체 정리)이 아직 확정되지 않아, 부하가 실측되기 전까지 두 경로를 병행한다(사람 확인).
 * 그 외(RECIPIENT_CONFIRMATION, AUTH_CODE)는 file 없이 proofValue 로 참조값을 직접 받는다.
 */
@Schema(description = "배송 완료 요청(사진 파일 또는 인증 참조값 — 서버 직접 업로드 경로)")
public record RiderDeliveryCompleteMultipartRequest(

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
