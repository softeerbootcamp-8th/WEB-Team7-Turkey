package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배송 완료 인증 사진 업로드용 presigned URL")
public record RiderDeliveryProofUploadUrlResponse(

        @Schema(description = "업로드 후 완료 요청(proofValue)에 그대로 실어 보낼 저장소 키",
                example = "proof/2026/08/1024/9c1e6b1a-....jpg")
        String key,

        @Schema(description = "이 URL로 PUT 요청을 보내면 업로드된다. 발급 시 지정한 Content-Type과 "
                + "동일한 Content-Type 헤더로 요청해야 서명이 일치한다.")
        String uploadUrl,

        @Schema(description = "URL 만료 시각(UTC)")
        LocalDateTime expiresAt
) {
}
