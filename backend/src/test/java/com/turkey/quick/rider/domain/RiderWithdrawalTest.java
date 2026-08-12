package com.turkey.quick.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("출금요청은 PENDING으로 생성되고 처리시각과 복구플래그는 비어있다")
    void shouldCreatePendingWithdrawalWithoutProcessedAtOrRestoredFlag() {
        RiderWithdrawal withdrawal = pending(30_000L);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(withdrawal.getAmount()).isEqualTo(30_000L);
        assertThat(withdrawal.getProcessedAt()).isNull();
        assertThat(withdrawal.isPointsRestored()).isFalse();
        assertThat(withdrawal.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("신청시점의 계좌 정보를 스냅샷으로 저장한다")
    void shouldStoreAccountSnapshotAtRequestTime() {
        RiderProfile rider = rider();

        RiderWithdrawal withdrawal = RiderWithdrawal.request(rider, "req-key-1", 30_000L,
                "004", "****5678", "박라이더");

        assertThat(withdrawal.getBankCodeSnapshot()).isEqualTo("004");
        assertThat(withdrawal.getMaskedAccountNumberSnapshot()).isEqualTo("****5678");
        assertThat(withdrawal.getAccountHolderNameSnapshot()).isEqualTo("박라이더");
        assertThat(withdrawal.getRider()).isSameAs(rider);
    }

    @Test
    @DisplayName("출금금액은 양수여야 한다")
    void shouldRequirePositiveWithdrawalAmount() {
        assertThatThrownBy(() -> pending(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청식별값은 비어있을 수 없다")
    void shouldRequireNonBlankRequestKey() {
        assertThatThrownBy(() -> RiderWithdrawal.request(rider(), "  ", 30_000L,
                "004", "****5678", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("라이더는 필수다")
    void shouldRequireRider() {
        assertThatThrownBy(() -> RiderWithdrawal.request(null, "req-key-1", 30_000L,
                "004", "****5678", "박라이더"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("계좌정보는 비어있을 수 없다")
    void shouldRequireNonBlankAccountInformation() {
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
    @DisplayName("이미 완료된 출금은 다시 처리할 수 없다")
    void shouldNotProcessCompletedWithdrawalAgain() {
        RiderWithdrawal withdrawal = pending(30_000L);
        withdrawal.complete();

        assertThatThrownBy(withdrawal::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawal.fail("사유")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 실패한 출금은 다시 처리할 수 없다")
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
