package com.turkey.quick.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RiderWithdrawalTest {

    private RiderPayoutAccount account() {
        RiderProfile rider = RiderProfile.create(
                Member.create("rider01", "hash", "박라이더", "01033334444", MemberRole.RIDER));
        return RiderPayoutAccount.register(rider, "004",
                "encrypted".getBytes(StandardCharsets.UTF_8), "1234-**-5678", "박라이더");
    }

    private RiderWithdrawal pending(long amount) {
        return RiderWithdrawal.request(account(), "11111111-2222-3333-4444-555555555555", amount);
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

    /** 계좌는 in-place 로 교체되므로, 이력이 현재 계좌를 따라가지 않도록 값을 복사해 둔다. */
    @Test
    void 요청_시점의_계좌_정보를_스냅샷으로_복사한다() {
        RiderPayoutAccount account = account();

        RiderWithdrawal withdrawal = RiderWithdrawal.request(account, "req-key-1", 30_000L);

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("1234-**-5678");
        assertThat(withdrawal.getAccountHolderNameSnapshot()).isEqualTo("박라이더");
        assertThat(withdrawal.getRider()).isSameAs(account.getRider());
    }

    @Test
    void 계좌를_바꿔도_기존_출금이력의_스냅샷은_변하지_않는다() {
        RiderPayoutAccount account = account();
        RiderWithdrawal withdrawal = RiderWithdrawal.request(account, "req-key-1", 30_000L);

        account.changeAccount("020", "new-encrypted".getBytes(StandardCharsets.UTF_8),
                "9999-**-0000", "박라이더");

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("1234-**-5678");
    }

    @Test
    void 출금금액은_양수여야_한다() {
        assertThatThrownBy(() -> pending(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요청식별값은_비어있을수_없다() {
        assertThatThrownBy(() -> RiderWithdrawal.request(account(), "  ", 30_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 출금계좌는_필수다() {
        assertThatThrownBy(() -> RiderWithdrawal.request(null, "req-key-1", 30_000L))
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
