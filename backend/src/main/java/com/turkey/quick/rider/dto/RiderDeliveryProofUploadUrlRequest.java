package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "배송 완료 인증 사진 업로드용 presigned URL 발급 요청")
public record RiderDeliveryProofUploadUrlRequest(

        @Schema(description = "업로드할 사진의 Content-Type(image/jpeg, image/png, image/webp만 허용)",
                example = "image/jpeg")
        @NotBlank
        String contentType
) {
}
