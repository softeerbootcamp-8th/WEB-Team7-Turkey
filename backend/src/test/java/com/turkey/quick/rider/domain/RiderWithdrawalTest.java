package com.turkey.quick.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.Test;

class RiderWithdrawalTest {

    private RiderProfile rider() {
        return RiderProfile.create(
                Member.create("rider01", "hash", "박라이더", "01033334444", MemberRole.RIDER));
    }

    private RiderWithdrawal pending(long amount) {
        return RiderWithdrawal.request(rider(), "11111111-2222-3333-4444-555555555555", amount,
                "004", "****5678", "박라이더");
    }

    @Test
    void 출금요청은_PENDING으로_생성되고_처리시각과_복구플래그는_비어있다() {
        RiderWithdrawal withdrawal = pending(30_000L);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(withdrawal.getAmount()).isEqualTo(30_000L);
        assertThat(withdrawal.getProcessedAt()).isNull();
        assertThat(withdrawal.isPointsRestored()).isFalse();
        assertThat(withdrawal.getFailureReason()).isNull();
    }

    @Test
    void 신청시점의_계좌_정보를_스냅샷으로_저장한다() {
        RiderProfile rider = rider();

        RiderWithdrawal withdrawal = RiderWithdrawal.request(rider, "req-key-1", 30_000L,
                "004", "****5678", "박라이더");

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("****5678");
        assertThat(withdrawal.getAccountHolderNameSnapshot()).isEqualTo("박라이더");
        assertThat(withdrawal.getRider()).isSameAs(rider);
    }

    @Test
    void 출금금액은_양수여야_한다() {
        assertThatThrownBy(() -> pending(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요청식별값은_비어있을수_없다() {
        assertThatThrownBy(() -> RiderWithdrawal.request(rider(), "  ", 30_000L,
                "004", "****5678", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 라이더는_필수다() {
        assertThatThrownBy(() -> RiderWithdrawal.request(null, "req-key-1", 30_000L,
                "004", "****5678", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계좌정보는_비어있을수_없다() {
        assertThatThrownBy(() -> RiderWithdrawal.request(rider(), "req-key-1", 30_000L,
                " ", "****5678", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiderWithdrawal.request(rider(), "req-key-1", 30_000L,
                "004", " ", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiderWithdrawal.request(rider(), "req-key-1", 30_000L,
                "004", "****5678", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** ck_rider_withdrawal_state_values: COMPLETED ⟺ processed_at NOT NULL, points_restored=0. */
    @Test
    void 완료하면_처리시각이_기록되고_복구플래그는_false로_남는다() {
        RiderWithdrawal withdrawal = pending(30_000L);

        withdrawal.complete();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getProcessedAt()).isNotNull();
        assertThat(withdrawal.isPointsRestored()).isFalse();
    }

    /** ck_rider_withdrawal_state_values: FAILED ⟺ processed_at NOT NULL, points_restored=1. */
    @Test
    void 실패하면_처리시각과_복구플래그를_함께_세팅한다() {
        RiderWithdrawal withdrawal = pending(30_000L);

        withdrawal.fail("계좌 정보 불일치");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(withdrawal.getProcessedAt()).isNotNull();
        assertThat(withdrawal.isPointsRestored()).isTrue();
        assertThat(withdrawal.getFailureReason()).isEqualTo("계좌 정보 불일치");
    }

    @Test
    void 이미_완료된_출금은_다시_처리할수_없다() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.complete();

        assertThatThrownBy(withdrawal::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawal.fail("사유")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_실패한_출금은_다시_처리할수_없다() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.fail("계좌 정보 불일치");

        assertThatThrownBy(withdrawal::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawal.fail("다른 사유"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 재처리를_거부해도_기존_상태는_그대로_유지된다() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.complete();

        assertThatThrownBy(() -> withdrawal.fail("사유")).isInstanceOf(IllegalStateException.class);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.isPointsRestored()).isFalse();
        assertThat(withdrawal.getFailureReason()).isNull();
    }
}
