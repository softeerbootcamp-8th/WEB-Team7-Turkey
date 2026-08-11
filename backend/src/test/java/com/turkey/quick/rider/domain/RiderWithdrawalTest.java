package com.turkey.quick.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("출금요청은 PENDING으로 생성되고 처리시각과 복구플래그는 비어있다")
    void shouldCreatePendingWithdrawalWithoutProcessedAtOrRestoredFlag() {
        RiderWithdrawal withdrawal = pending(30_000L);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(withdrawal.getAmount()).isEqualTo(30_000L);
        assertThat(withdrawal.getProcessedAt()).isNull();
        assertThat(withdrawal.isPointsRestored()).isFalse();
        assertThat(withdrawal.getFailureReason()).isNull();
    }

    /** 계좌는 in-place 로 교체되므로, 이력이 현재 계좌를 따라가지 않도록 값을 복사해 둔다. */
    @Test
    @DisplayName("요청 시점의 계좌 정보를 스냅샷으로 복사한다")
    void shouldCopyAccountInformationIntoRequestSnapshot() {
        RiderPayoutAccount account = account();

        RiderWithdrawal withdrawal = RiderWithdrawal.request(account, "req-key-1", 30_000L);

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("1234-**-5678");
        assertThat(withdrawal.getAccountHolderNameSnapshot()).isEqualTo("박라이더");
        assertThat(withdrawal.getRider()).isSameAs(account.getRider());
    }

    @Test
    @DisplayName("계좌를 바꿔도 기존 출금이력의 스냅샷은 변하지 않는다")
    void shouldPreserveWithdrawalSnapshotAfterAccountChange() {
        RiderPayoutAccount account = account();
        RiderWithdrawal withdrawal = RiderWithdrawal.request(account, "req-key-1", 30_000L);

        account.changeAccount("020", "new-encrypted".getBytes(StandardCharsets.UTF_8),
                "9999-**-0000", "박라이더");

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("1234-**-5678");
    }

    @Test
    @DisplayName("출금금액은 양수여야 한다")
    void shouldRequirePositiveWithdrawalAmount() {
        assertThatThrownBy(() -> pending(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청식별값은 비어있을수 없다")
    void shouldRequireNonBlankRequestKey() {
        assertThatThrownBy(() -> RiderWithdrawal.request(account(), "  ", 30_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("출금계좌는 필수다")
    void shouldRequirePayoutAccount() {
        assertThatThrownBy(() -> RiderWithdrawal.request(null, "req-key-1", 30_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** ck_rider_withdrawal_state_values: COMPLETED ⟺ processed_at NOT NULL, points_restored=0. */
    @Test
    @DisplayName("완료하면 처리시각이 기록되고 복구플래그는 false로 남는다")
    void shouldRecordProcessedAtAndLeaveRestoredFalseOnCompletion() {
        RiderWithdrawal withdrawal = pending(30_000L);

        withdrawal.complete();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getProcessedAt()).isNotNull();
        assertThat(withdrawal.isPointsRestored()).isFalse();
    }

    /** ck_rider_withdrawal_state_values: FAILED ⟺ processed_at NOT NULL, points_restored=1. */
    @Test
    @DisplayName("실패하면 처리시각과 복구플래그를 함께 세팅한다")
    void shouldSetProcessedAtAndRestoredFlagOnFailure() {
        RiderWithdrawal withdrawal = pending(30_000L);

        withdrawal.fail("계좌 정보 불일치");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(withdrawal.getProcessedAt()).isNotNull();
        assertThat(withdrawal.isPointsRestored()).isTrue();
        assertThat(withdrawal.getFailureReason()).isEqualTo("계좌 정보 불일치");
    }

    @Test
    @DisplayName("이미 완료된 출금은 다시 처리할수 없다")
    void shouldNotProcessCompletedWithdrawalAgain() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.complete();

        assertThatThrownBy(withdrawal::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawal.fail("사유")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 실패한 출금은 다시 처리할수 없다")
    void shouldNotProcessFailedWithdrawalAgain() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.fail("계좌 정보 불일치");

        assertThatThrownBy(withdrawal::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawal.fail("다른 사유"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("재처리를 거부해도 기존 상태는 그대로 유지된다")
    void shouldPreserveExistingStateWhenReprocessingIsRejected() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.complete();

        assertThatThrownBy(() -> withdrawal.fail("사유")).isInstanceOf(IllegalStateException.class);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.isPointsRestored()).isFalse();
        assertThat(withdrawal.getFailureReason()).isNull();
    }
}
