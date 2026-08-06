package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    /**
     * 타임라인을 {@code delivery_order} 의 단계별 시각 컬럼에서 파생한다(도달한 단계만, 선언 순서가
     * 곧 시간 순서). {@code order_status_history} 는 엔터티만 있고 행을 쓰는 코드가 없어 런타임에
     * 비어 있으므로 시각 컬럼이 지금 신뢰할 수 있는 유일한 출처다 —
     * {@code DeliveryTrackingQueryService.steps}·{@code DeliveryDetailResponse.steps}와 같은 판단이며,
     * 세 번째 사용처인 라이더 운행 기록 상세(#71)가 복제 대신 이 공용 팩토리를 쓴다.
     * 완료 배송 기록에는 CANCELED 가 실제로 나오지 않지만, 취소 단계까지 포함해 두어도 무해하다.
     */
    public static List<DeliveryStatusStepResponse> timelineOf(DeliveryOrder order) {
        List<DeliveryStatusStepResponse> steps = new ArrayList<>();
        addStep(steps, OrderStatus.WAITING, order.getRequestedAt());
        addStep(steps, OrderStatus.ASSIGNED, order.getAssignedAt());
        addStep(steps, OrderStatus.MOVING_TO_PICKUP, order.getMovingToPickupAt());
        addStep(steps, OrderStatus.PICKED_UP, order.getPickedUpAt());
        addStep(steps, OrderStatus.DELIVERING, order.getDeliveringAt());
        addStep(steps, OrderStatus.COMPLETED, order.getCompletedAt());
        addStep(steps, OrderStatus.CANCELED, order.getCanceledAt());
        return List.copyOf(steps);
    }

    private static void addStep(List<DeliveryStatusStepResponse> steps, OrderStatus status,
                                LocalDateTime occurredAt) {
        if (occurredAt != null) {
            steps.add(new DeliveryStatusStepResponse(status, occurredAt));
        }
    }
}
