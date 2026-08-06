package com.turkey.quick.rider.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiderDeliveryNextActionTest {

    @Test
    @DisplayName("진행 중 배송 상태는 화면에서 수행할 다음 행동으로 변환된다")
    void shouldMapInProgressStatusToNextAction() {
        assertThat(RiderDeliveryNextAction.from(OrderStatus.ASSIGNED))
                .isEqualTo(RiderDeliveryNextAction.START_MOVING_TO_PICKUP);
        assertThat(RiderDeliveryNextAction.from(OrderStatus.MOVING_TO_PICKUP))
                .isEqualTo(RiderDeliveryNextAction.PICK_UP);
        assertThat(RiderDeliveryNextAction.from(OrderStatus.PICKED_UP))
                .isEqualTo(RiderDeliveryNextAction.START_DELIVERING);
        assertThat(RiderDeliveryNextAction.from(OrderStatus.DELIVERING))
                .isEqualTo(RiderDeliveryNextAction.COMPLETE);
    }

    @Test
    @DisplayName("진행 중 상태가 아니면 다음 행동을 계산하지 않는다")
    void shouldRejectStatusThatIsNotInProgress() {
        assertThatThrownBy(() -> RiderDeliveryNextAction.from(OrderStatus.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
