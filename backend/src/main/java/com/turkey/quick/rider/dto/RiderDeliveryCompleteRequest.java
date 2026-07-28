package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.ProofType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 배송 완료 요청.
 *
 * 이미지 바이너리를 여기로 받지 않는다 — delivery_proof 는 참조값(URL/스토리지 키/인증코드)만
 * 보관하도록 설계돼 있다. 사진 업로드는 별도 경로(업로드 API 또는 presigned URL)로 끝낸 뒤
 * 그 결과 키를 proofValue 로 넘긴다. 완료 트랜잭션(상태 전이 + 라이더 해제 + 정산 생성)에
 * 파일 전송 시간을 끌어들이지 않으려는 목적도 있다.
 *
 * 업로드 경로 자체는 아직 미확정이라 이 API 범위 밖이다.
 */
@Schema(description = "배송 완료 요청(인증 참조값)")
public record RiderDeliveryCompleteRequest(

        @Schema(description = "인증 방식")
        @NotNull
        ProofType proofType,

        @Schema(description = "인증 참조값(사진 URL/스토리지 키, 수령인 확인 참조, 인증코드)",
                example = "proof/2026/07/1024.jpg")
        @NotBlank @Size(max = 500)
        String proofValue
) {
}
