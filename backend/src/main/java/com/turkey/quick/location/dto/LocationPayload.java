package com.turkey.quick.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record LocationPayload(
        @Schema(description = "위도", example = "37.4979")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0276")
        BigDecimal longitude,

        @Schema(description = "측정 시각(UTC)", example = "2026-08-03T01:02:03.456Z")
        Instant measuredAt,

        @Schema(description = "측정 정확도(미터). 없으면 null", example = "12.5")
        BigDecimal accuracyMeters
) {}
