package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("생성하면 INACTIVE 상태이고 적용종료시각과 할증은 비어있다")
    void shouldCreateInactivePolicyWithoutEffectiveToOrSurcharges() {
        FarePolicy policy = policy();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNull();
        assertThat(policy.getSurcharges()).isEmpty();
    }

    @Test
    @DisplayName("기본요금은 양수여야 한다")
    void shouldRequirePositiveBaseFare() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 0L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거리단위는 양수여야 한다")
    void shouldRequirePositiveDistanceUnit() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 0, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거리단가는 양수여야 한다")
    void shouldRequirePositiveFarePerDistanceUnit() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 0L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최대배송거리는 양수여야 한다")
    void shouldRequirePositiveMaximumDeliveryDistance() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 0,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정책버전은 null일수 없다")
    void shouldRejectNullPolicyVersion() {
        assertThatThrownBy(() ->
                FarePolicy.create(null, 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정책버전은 공백일수 없다")
    void shouldRejectBlankPolicyVersion() {
        assertThatThrownBy(() ->
                FarePolicy.create("   ", 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정책버전은 30자를 초과할수 없다")
    void shouldRejectPolicyVersionLongerThanThirtyCharacters() {
        String tooLong = "v".repeat(31);

        assertThatThrownBy(() ->
                FarePolicy.create(tooLong, 3_000L, 1_000, 500L, 10_000,
                        LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("적용시작시각은 null일수 없다")
    void shouldRejectNullEffectiveFrom() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("activate하면 ACTIVE로 전이한다")
    void shouldTransitionToActiveOnActivation() {
        FarePolicy policy = policy();

        policy.activate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 ACTIVE인 정책은 다시 activate할수 없다")
    void shouldNotActivateAlreadyActivePolicy() {
        FarePolicy policy = policy();
        policy.activate();

        assertThatThrownBy(policy::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("deactivate하면 INACTIVE로 전이하고 적용종료시각이 기록된다")
    void shouldTransitionToInactiveAndRecordEffectiveToOnDeactivation() {
        FarePolicy policy = policy();
        policy.activate();

        policy.deactivate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNotNull();
    }

    @Test
    @DisplayName("INACTIVE 상태는 deactivate할수 없다")
    void shouldNotDeactivateInactivePolicy() {
        FarePolicy policy = policy();

        assertThatThrownBy(policy::deactivate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("한번 비활성화된 정책은 재활성화할수 없다")
    void shouldNotReactivatePreviouslyDeactivatedPolicy() {
        FarePolicy policy = policy();
        policy.activate();
        policy.deactivate();

        assertThatThrownBy(policy::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("적용시작시각이 미래이면 deactivate는 명확한 예외로 실패한다")
    void shouldFailClearlyWhenDeactivatingPolicyThatStartsInFuture() {
        LocalDateTime farFuture = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);
        FarePolicy policy = FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, farFuture);
        policy.activate();

        assertThatThrownBy(policy::deactivate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("물품종류 할증을 추가할수 있다")
    void shouldAddItemTypeSurcharge() {
        FarePolicy policy = policy();

        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThat(policy.getSurcharges()).hasSize(1);
        assertThat(policy.getSurcharges().get(0).getItemType()).isEqualTo(ItemType.FOOD);
        assertThat(policy.getSurcharges().get(0).getSurchargeAmount()).isEqualTo(1_000L);
        assertThat(policy.getSurcharges().get(0).getFarePolicy()).isSameAs(policy);
    }

    @Test
    @DisplayName("같은 물품종류를 중복 추가할수 없다")
    void shouldNotAddDuplicateItemTypeSurcharge() {
        FarePolicy policy = policy();
        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, 500L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("할증금액은 음수일수 없다")
    void shouldRejectNegativeSurchargeAmount() {
        FarePolicy policy = policy();

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getSurcharges는 수정불가능한 리스트를 반환한다")
    void shouldReturnUnmodifiableSurchargeList() {
        FarePolicy policy = policy();
        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThatThrownBy(() -> policy.getSurcharges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.getSurcharges().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
