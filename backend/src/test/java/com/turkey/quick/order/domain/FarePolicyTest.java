package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FarePolicyTest {

    private FarePolicy policy() {
        return FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, LocalDateTime.now());
    }

    @Test
    void 생성하면_INACTIVE_상태이고_적용종료시각과_할증은_비어있다() {
        FarePolicy policy = policy();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNull();
        assertThat(policy.getSurcharges()).isEmpty();
    }

    @Test
    void 기본요금은_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 0L, 1_000, 500L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단위는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 0, 500L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단가는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 0L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최대배송거리는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 0, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activate하면_ACTIVE로_전이한다() {
        FarePolicy policy = policy();

        policy.activate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.ACTIVE);
    }

    @Test
    void 이미_ACTIVE인_정책은_다시_activate할수_없다() {
        FarePolicy policy = policy();
        policy.activate();

        assertThatThrownBy(policy::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deactivate하면_INACTIVE로_전이하고_적용종료시각이_기록된다() {
        FarePolicy policy = policy();
        policy.activate();

        policy.deactivate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNotNull();
    }

    @Test
    void INACTIVE_상태는_deactivate할수_없다() {
        FarePolicy policy = policy();

        assertThatThrownBy(policy::deactivate).isInstanceOf(IllegalStateException.class);
    }
}
