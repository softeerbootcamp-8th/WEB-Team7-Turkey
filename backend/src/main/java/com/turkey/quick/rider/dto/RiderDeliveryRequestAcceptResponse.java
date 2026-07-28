package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.OperatingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배차 확정 결과.
 *
 * 배송 WAITING→ASSIGNED, 라이더 AVAILABLE→BUSY 가 한 트랜잭션에서 함께 일어나므로
 * 두 상태를 같이 돌려준다 — 프론트가 배차 성공 후 별도 조회 없이 진행 배송 화면으로 넘어갈 수 있다.
 *
 * 경쟁에서 진 요청은 이 응답을 받지 않는다. 부분 성공이 없으므로 409 로 명확히 실패한다.
 */
@Schema(description = "배차 확정 결과")
public record RiderDeliveryRequestAcceptResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(확정 시 ASSIGNED)")
        OrderStatus status,

        @Schema(description = "라이더 운행 상태(확정 시 BUSY)")
        OperatingStatus operatingStatus,

        @Schema(description = "배차 시각(UTC)", example = "2026-07-28T02:12:00")
        LocalDateTime assignedAt
) {
}
