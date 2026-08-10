package com.turkey.quick.location.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 배송 상태 전이를 알리는 SSE 팬아웃 프레임(#398, Discussion #375). {@link LocationPayload}와 같은
 * {@code tracking:order:{id}} 채널을 공유하고, {@code type}으로 프론트가 두 프레임을 구분한다.
 */
public record StatusChangedPayload(
        @Schema(description = "프레임 판별자, 항상 \"status\"", example = "status")
        String type,

        @Schema(description = "전이된 배송 상태")
        OrderStatus status,

        @Schema(description = "전이 시각(UTC)", example = "2026-08-03T01:02:03.456Z")
        Instant occurredAt
) {
    public StatusChangedPayload(OrderStatus status, Instant occurredAt) {
        this("status", status, occurredAt);
    }
}
