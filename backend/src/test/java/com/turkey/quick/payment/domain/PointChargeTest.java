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
    void 충전요청은_PENDING_상태로_생성되고_승인_환불_필드는_비어있다() {
        PointCharge charge = pending(10_000L);

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(charge.getRequestedAmount()).isEqualTo(10_000L);
        assertThat(charge.getApprovedAmount()).isNull();
        assertThat(charge.getRefundedAmount()).isZero();
    }

    @Test
    void 요청금액은_양수여야_한다() {
        assertThatThrownBy(() -> pending(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인하면_PAID로_전이하고_승인금액은_요청금액과_같다() {
        PointCharge charge = pending(10_000L);

        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PAID);
        assertThat(charge.getApprovedAmount()).isEqualTo(10_000L);
        assertThat(charge.getApprovedAt()).isNotNull();
        assertThat(charge.getProviderPaymentKey()).isEqualTo("pay-key-1");
        assertThat(charge.getRefundedAmount()).isZero();
    }

    @Test
    void 이미_승인된_충전은_다시_승인할수_없다() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThatThrownBy(() -> charge.approve("pay-key-2", "IBK", "국민카드(1234)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패하면_FAILED로_전이하고_사유를_기록한다() {
        PointCharge charge = pending(10_000L);

        charge.fail("한도 초과");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.FAILED);
        assertThat(charge.getFailureReason()).isEqualTo("한도 초과");
        assertThat(charge.getApprovedAmount()).isNull();
    }

    @Test
    void 취소하면_CANCELED로_전이하고_사유를_기록한다() {
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
    void 이미_승인된_충전은_취소할수_없다() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        assertThatThrownBy(() -> charge.cancel("고객이 결제를 취소했습니다."))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_실패한_충전은_취소할수_없다() {
        PointCharge charge = pending(10_000L);
        charge.fail("한도 초과");

        // 서비스는 이 경우 예외로 새지 않고 200 멱등 응답으로 흡수한다(#34).
        // 도메인 레벨에서는 PENDING 이 아닌 전이를 거부하는 것이 맞다.
        assertThatThrownBy(() -> charge.cancel("고객이 결제를 취소했습니다."))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 승인된_충전을_전액_환불하면_REFUNDED로_전이한다() {
        PointCharge charge = pending(10_000L);
        charge.approve("pay-key-1", "IBK", "국민카드(1234)");

        charge.refund("refund-key-1", "고객 변심");

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.REFUNDED);
        assertThat(charge.getRefundedAmount()).isEqualTo(10_000L);
        assertThat(charge.getRefundedAt()).isNotNull();
        assertThat(charge.getProviderRefundKey()).isEqualTo("refund-key-1");
    }

    @Test
    void 승인되지_않은_충전은_환불할수_없다() {
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
