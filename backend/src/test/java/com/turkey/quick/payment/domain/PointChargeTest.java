package com.turkey.quick.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PointChargeTest {

    private Member customer() {
        return Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER);
    }

    private PointCharge pending(long amount) {
        return PointCharge.request(customer(), "req-key-1", PaymentMethod.CARD, amount, "MOCK_PG");
    }

    @Test
    @DisplayName("충전요청은 PENDING 상태로 생성되고 승인 환불 필드는 비어있다")
    void shouldCreatePendingChargeWithEmptyApprovalAndRefundFields() {
        PointCharge charge = pending(10_000L);

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(charge.getRequestedAmount()).isEqualTo(10_000L);
        assertThat(charge.getApprovedAmount()).isNull();
        assertThat(charge.getRefundedAmount()).isZero();
    }

    @Test
    @DisplayName("요청금액은 양수여야 한다")
    void shouldRequirePositiveRequestedAmount() {
        assertThatThrownBy(() -> pending(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("승인하면 PAID로 전이하고 승인금액은 요청금액과 같다")
    void shouldTransitionToPaidWithRequestedAmountOnApproval() {
        PointCharge charge = pending(10_000L);

        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PAID);
        assertThat(charge.getApprovedAmount()).isEqualTo(10_000L);
        assertThat(charge.getApprovedAt()).isNotNull();
        assertThat(charge.getProviderPaymentKey()).isEqualTo("pay-key-1");
        assertThat(charge.getRefundedAmount()).isZero();
    }

    @Test
    @DisplayName("이미 승인된 충전은 다시 승인할수 없다")
    void shouldNotApproveAlreadyPaidCharge() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThatThrownBy(() -> charge.approve("pay-key-2", "IBK", "국민카드(1234)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("실패하면 FAILED로 전이하고 사유를 기록한다")
    void shouldTransitionToFailedAndRecordReason() {
        PointCharge charge = pending(10_000L);

        charge.fail("한도 초과");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.FAILED);
        assertThat(charge.getFailureReason()).isEqualTo("한도 초과");
        assertThat(charge.getApprovedAmount()).isNull();
    }

    @Test
    @DisplayName("취소하면 CANCELED로 전이하고 사유를 기록한다")
    void shouldTransitionToCanceledAndRecordReason() {
        PointCharge charge = pending(10_000L);

        charge.cancel("고객이 결제를 취소했습니다.");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.CANCELED);
        // CANCELED 전용 사유 컬럼을 두지 않고 failure_reason 을 겸용한다(#34).
        assertThat(charge.getFailureReason()).isEqualTo("고객이 결제를 취소했습니다.");
        // ck_point_charge_state_values: CANCELED 행에는 승인 금액·시각이 없어야 한다.
        assertThat(charge.getApprovedAmount()).isNull();
        assertThat(charge.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("이미 승인된 충전은 취소할수 없다")
    void shouldNotCancelAlreadyPaidCharge() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThatThrownBy(() -> charge.cancel("고객이 결제를 취소했습니다."))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 실패한 충전은 취소할수 없다")
    void shouldNotCancelFailedCharge() {
        PointCharge charge = pending(10_000L);
        charge.fail("한도 초과");

        // 서비스는 이 경우 예외로 새지 않고 200 멱등 응답으로 흡수한다(#34).
        // 도메인 레벨에서는 PENDING 이 아닌 전이를 거부하는 것이 맞다.
        assertThatThrownBy(() -> charge.cancel("고객이 결제를 취소했습니다."))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("승인된 충전을 전액 환불하면 REFUNDED로 전이한다")
    void shouldTransitionToRefundedAfterFullRefund() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        charge.refund("refund-key-1", "고객 변심");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.REFUNDED);
        assertThat(charge.getRefundedAmount()).isEqualTo(10_000L);
        assertThat(charge.getRefundedAt()).isNotNull();
        assertThat(charge.getProviderRefundKey()).isEqualTo("refund-key-1");
    }

    @Test
    @DisplayName("승인되지 않은 충전은 환불할수 없다")
    void shouldNotRefundUnpaidCharge() {
        PointCharge charge = pending(10_000L);

        assertThatThrownBy(() -> charge.refund("refund-key-1", "고객 변심"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    @DisplayName("승인 응답을 기록하면 상태는 PENDING 그대로고 승인 식별자만 남는다")
    void recordsApprovalKeyWithoutChangingStatus() {
        PointCharge charge = pending(10_000L);

        boolean recorded = charge.markApprovalReceived("pay-key-1");

        // 이 조합(PENDING + 승인식별자)이 "PG 승인은 받았는데 반영이 안 끝난 건"을 뜻한다
        assertThat(recorded).isTrue();
        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(charge.getProviderPaymentKey()).isEqualTo("pay-key-1");
        assertThat(charge.getApprovedAmount()).isNull();
        assertThat(charge.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("이미 기록된 승인 식별자는 다른 값으로 덮이지 않는다")
    void doesNotOverwriteRecordedApprovalKey() {
        PointCharge charge = pending(10_000L);
        charge.markApprovalReceived("pay-key-first");

        boolean recorded = charge.markApprovalReceived("pay-key-second");

        // 덮어쓰면 먼저 받은 승인이 어디에도 안 남아 추적할 수 없게 된다
        assertThat(recorded).isFalse();
        assertThat(charge.getProviderPaymentKey()).isEqualTo("pay-key-first");
    }

    @Test
    @DisplayName("같은 승인 식별자를 다시 기록하면 성공으로 본다")
    void treatsSameApprovalKeyAsRecorded() {
        PointCharge charge = pending(10_000L);
        charge.markApprovalReceived("pay-key-1");

        assertThat(charge.markApprovalReceived("pay-key-1")).isTrue();
    }

    @Test
    @DisplayName("승인은 이미 기록된 승인 식별자를 덮어쓰지 않는다")
    void approveKeepsRecordedApprovalKey() {
        PointCharge charge = pending(10_000L);
        charge.markApprovalReceived("pay-key-first");

        charge.approve("pay-key-second", "IBK", "국민카드(1234)");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PAID);
        assertThat(charge.getProviderPaymentKey()).isEqualTo("pay-key-first");
    }
}
