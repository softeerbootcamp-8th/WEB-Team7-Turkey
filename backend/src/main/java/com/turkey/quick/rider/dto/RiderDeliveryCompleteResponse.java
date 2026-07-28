package com.turkey.quick.rider.dto;

import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.OperatingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 배송 완료 결과.
 *
 * 배송 DELIVERING→COMPLETED, 라이더 BUSY→AVAILABLE, 정산 생성이 한 트랜잭션이므로
 * 세 결과를 함께 돌려준다. 정산 행의 존재 자체가 정산 완료를 뜻하므로 별도 정산 상태는 없다 —
 * 정산 생성이 실패하면 완료 자체가 롤백되고 이 응답은 나가지 않는다.
 */
@Schema(description = "배송 완료 결과")
public record RiderDeliveryCompleteResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(완료 시 COMPLETED)")
        OrderStatus status,

        @Schema(description = "라이더 운행 상태(완료 시 AVAILABLE)")
        OperatingStatus operatingStatus,

        @Schema(description = "확정된 정산 금액(원)", example = "6400")
        Long settlementAmount,

        @Schema(description = "완료 시각(UTC)", example = "2026-07-28T02:47:00")
        LocalDateTime completedAt
) {
}
