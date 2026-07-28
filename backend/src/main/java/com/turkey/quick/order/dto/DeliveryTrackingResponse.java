package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송 추적 화면의 초기 스냅샷.
 *
 * 라이더의 실시간 좌표는 여기 담지 않는다 — 위치는 location 도메인의 SSE 스트림이 전달한다.
 * 이 응답은 화면 진입 시 한 번 그릴 상태·타임라인·상대 정보만 제공하고,
 * 이후 갱신은 SSE 가 담당한다(폴링하지 않는다).
 */
@Schema(description = "배송 추적 스냅샷")
public record DeliveryTrackingResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "현재 배송 상태")
        OrderStatus status,

        @Schema(description = "상태 전이 타임라인(도달한 단계만)")
        List<DeliveryStatusStepResponse> steps,

        @Schema(description = "배차된 라이더 이름. 배차 전이면 null.", example = "박라이더")
        String riderName,

        @Schema(description = "배차된 라이더 연락처. 배차 전이면 null.", example = "010-9876-5432")
        String riderPhoneNumber,

        @Schema(description = "도착 예정 시각(UTC). 산정 불가하면 null.",
                example = "2026-07-28T02:47:00")
        LocalDateTime estimatedArrivalAt,

        @Schema(description = "결제 금액(원)", example = "6400")
        Long totalFare
) {
}
