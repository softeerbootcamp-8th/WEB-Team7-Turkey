package com.turkey.quick.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * SSE 팬아웃 프레임의 {@code type} 판별 필드는 이 레코드가 위치 프레임임을 알린다(#398, Discussion
 * #375). {@code type}을 받지 않는 4-인자 생성자를 남겨 둔 이유: 이 레코드는 SSE 팬아웃 JSON뿐 아니라
 * {@code RiderLocationRepository}의 쉼표 구분 Redis 저장 형식에도 쓰이는데, 그쪽 {@code encode}는
 * 이 4개 필드만 위치 인자로 읽는다 — {@code type}을 추가해도 기존 호출부(생성 코드·테스트)를 전혀
 * 건드리지 않는다.
 */
public record LocationPayload(
        @Schema(description = "프레임 판별자, 항상 \"location\"", example = "location")
        String type,

        @Schema(description = "위도", example = "37.4979")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0276")
        BigDecimal longitude,

        @Schema(description = "측정 시각(UTC)", example = "2026-08-03T01:02:03.456Z")
        Instant measuredAt,

        @Schema(description = "측정 정확도(미터). 없으면 null", example = "12.5")
        BigDecimal accuracyMeters
) {
    public LocationPayload(BigDecimal latitude, BigDecimal longitude, Instant measuredAt, BigDecimal accuracyMeters) {
        this("location", latitude, longitude, measuredAt, accuracyMeters);
    }
}
