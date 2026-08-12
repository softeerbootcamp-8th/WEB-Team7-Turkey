package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("생성하면 주문과 요금정책을 참조하고 필드가 설정된다")
    void shouldCreateSnapshotWithOrderPolicyAndFields() {
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
    @DisplayName("total fare는 base distance surcharge의 합으로 자동 계산된다")
    void shouldCalculateTotalFareFromBaseDistanceAndSurcharge() {
        OrderFareSnapshot snapshot = OrderFareSnapshot.create(order(), farePolicy(), FareType.FINAL,
                "v1", 5_000, 3_000L, 2_500L, 1_000L);

        assertThat(snapshot.getTotalFare()).isEqualTo(6_500L);
    }

    @Test
    @DisplayName("할증이 0이어도 합으로 계산된다")
    void shouldCalculateTotalFareWhenSurchargeIsZero() {
        OrderFareSnapshot snapshot = OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 0L);

        assertThat(snapshot.getTotalFare()).isEqualTo(5_500L);
    }

    @Test
    @DisplayName("order는 null일수 없다")
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(null, farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("farePolicy는 null일수 없다")
    void shouldRejectNullFarePolicy() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), null, FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fareType은 null일수 없다")
    void shouldRejectNullFareType() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), null,
                "v1", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("policyVersion은 null일수 없다")
    void shouldRejectNullPolicyVersion() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                null, 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("policyVersion은 공백일수 없다")
    void shouldRejectBlankPolicyVersion() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "   ", 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("policyVersion은 30자를 초과할수 없다")
    void shouldRejectPolicyVersionLongerThanThirtyCharacters() {
        String tooLong = "v".repeat(31);

        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                tooLong, 5_000, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("산정거리는 양수여야 한다")
    void shouldRequirePositiveCalculatedDistance() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 0, 3_000L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본요금은 음수일수 없다")
    void shouldRejectNegativeBaseFare() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, -1L, 2_500L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거리요금은 음수일수 없다")
    void shouldRejectNegativeDistanceFare() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, -1L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("물품할증은 음수일수 없다")
    void shouldRejectNegativeItemSurcharge() {
        assertThatThrownBy(() -> OrderFareSnapshot.create(order(), farePolicy(), FareType.ESTIMATE,
                "v1", 5_000, 3_000L, 2_500L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
