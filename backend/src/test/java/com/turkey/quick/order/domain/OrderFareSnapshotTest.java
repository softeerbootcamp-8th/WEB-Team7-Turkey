package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderFareSnapshotTest {

    // OrderFareSnapshot 팩토리는 연관 객체를 null 검사 후 보관만 하므로,
    // 같은 패키지에서 접근 가능한 protected 무인자 생성자로 가벼운 인스턴스를 만든다.
    private DeliveryOrder order() {
        return new DeliveryOrder();
    }

    private FarePolicy farePolicy() {
        return new FarePolicy();
    }

    private OrderFareSnapshot snapshot() {
        return OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L);
    }

    @Test
    void 생성하면_주문과_요금정책을_참조하고_필드가_설정된다() {
        DeliveryOrder order = order();
        FarePolicy policy = farePolicy();

        OrderFareSnapshot snapshot = OrderFareSnapshot.create(order, policy, FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L);

        assertThat(snapshot.getOrder()).isSameAs(order);
        assertThat(snapshot.getFarePolicy()).isSameAs(policy);
        assertThat(snapshot.getFareType()).isEqualTo(FareType.ESTIMATE);
        assertThat(snapshot.getPolicyVersion()).isEqualTo("v1");
        assertThat(snapshot.getCalculationDistanceMeters()).isEqualTo(5_000);
        assertThat(snapshot.getBaseFare()).isEqualTo(3_000L);
        assertThat(snapshot.getDistanceFare()).isEqualTo(2_500L);
        assertThat(snapshot.getItemSurcharge()).isEqualTo(1_000L);
    }

    @Test
    void total_fare는_base_distance_surcharge의_합으로_자동_계산된다() {
        OrderFareSnapshot snapshot = OrderFareSnapshot.create(order(), farePolicy(), FareType.FINAL,
                "v1", 5_000, 3_000L, 2_500L, 1_000L);

        assertThat(snapshot.getTotalFare()).isEqualTo(6_500L);
    }

    @Test
    void 할증이_0이어도_합으로_계산된다() {
        OrderFareSnapshot snapshot = OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 0L);

        assertThat(snapshot.getTotalFare()).isEqualTo(5_500L);
    }

    @Test
    void order는_null일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(null, farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void farePolicy는_null일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), null, FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fareType은_null일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), null,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyVersion은_null일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                null, 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyVersion은_공백일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "   ", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyVersion은_30자를_초과할수_없다() {
        String tooLong = "v".repeat(31);

        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                tooLong, 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 산정거리는_양수여야_한다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 0, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기본요금은_음수일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, -1L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리요금은_음수일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, -1L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 물품할증은_음수일수_없다() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
