package com.turkey.quick.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
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
    void 취소하면_CANCELED로_전이한다() {
        PointCharge charge = pending(10_000L);

        charge.cancel();

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.CANCELED);
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
}
