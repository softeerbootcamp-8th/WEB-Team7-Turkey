package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배송 상태 타임라인의 한 단계. 고객 추적 화면과 라이더 이력 상세가 같은 형태로 쓴다.
 * 아직 도달하지 않은 단계는 목록에 담지 않는다(occurredAt 이 null 인 행을 만들지 않는다).
 */
@Schema(description = "배송 상태 전이 한 건")
public record DeliveryStatusStepResponse(

        @Schema(description = "전이된 상태")
        OrderStatus status,

        @Schema(description = "전이 시각(UTC)", example = "2026-07-28T02:11:00")
        LocalDateTime occurredAt
) {
}
