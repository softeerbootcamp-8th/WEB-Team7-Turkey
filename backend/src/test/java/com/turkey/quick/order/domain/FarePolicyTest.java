package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FarePolicyTest {

    private FarePolicy policy() {
        // effectiveFrom 을 충분히 과거로 두어, activate() 직후 deactivate() 를 호출해도
        // 밀리초 단위 시계 해상도 때문에 effectiveTo(now) <= effectiveFrom 이 되는
        // 우연한 충돌을 피한다.
        return FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000,
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
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
                FarePolicy.create("v1", 0L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단위는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 0, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단가는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 0L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최대배송거리는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 0,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정책버전은_null일수_없다() {
        assertThatThrownBy(() ->
                FarePolicy.create(null, 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정책버전은_공백일수_없다() {
        assertThatThrownBy(() ->
                FarePolicy.create("   ", 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정책버전은_30자를_초과할수_없다() {
        String tooLong = "v".repeat(31);

        assertThatThrownBy(() ->
                FarePolicy.create(tooLong, 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 적용시작시각은_null일수_없다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, null))
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

    @Test
    void 한번_비활성화된_정책은_재활성화할수_없다() {
        FarePolicy policy = policy();
        policy.activate();
        policy.deactivate();

        assertThatThrownBy(policy::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 적용시작시각이_미래이면_deactivate는_명확한_예외로_실패한다() {
        LocalDateTime farFuture = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);
        FarePolicy policy = FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, farFuture);
        policy.activate();

        assertThatThrownBy(policy::deactivate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 물품종류_할증을_추가할수_있다() {
        FarePolicy policy = policy();

        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThat(policy.getSurcharges()).hasSize(1);
        assertThat(policy.getSurcharges().get(0).getItemType()).isEqualTo(ItemType.FOOD);
        assertThat(policy.getSurcharges().get(0).getSurchargeAmount()).isEqualTo(1_000L);
        assertThat(policy.getSurcharges().get(0).getFarePolicy()).isSameAs(policy);
    }

    @Test
    void 같은_물품종류를_중복_추가할수_없다() {
        FarePolicy policy = policy();
        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, 500L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 할증금액은_음수일수_없다() {
        FarePolicy policy = policy();

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSurcharges는_수정불가능한_리스트를_반환한다() {
        FarePolicy policy = policy();
        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThatThrownBy(() -> policy.getSurcharges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.getSurcharges().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
