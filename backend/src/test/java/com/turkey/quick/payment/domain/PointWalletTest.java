package com.turkey.quick.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PointWalletTest {

    private Member member() {
        return Member.create("cust01", "hash", "김고객", "01033334444", MemberRole.CUSTOMER);
    }

    @Test
    @DisplayName("지갑 생성시 잔액은 0이다")
    void shouldCreateWalletWithZeroBalance() {
        PointWallet wallet = PointWallet.create(member());

        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    @DisplayName("충전하면 잔액이 증가한다")
    void shouldIncreaseBalanceWhenCharged() {
        PointWallet wallet = PointWallet.create(member());

        wallet.credit(1000L);

        assertThat(wallet.getBalance()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("사용하면 잔액이 감소한다")
    void shouldDecreaseBalanceWhenUsed() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(1000L);

        wallet.debit(300L);

        assertThat(wallet.getBalance()).isEqualTo(700L);
    }

    @Test
    @DisplayName("잔액보다 큰 금액은 사용할수 없고 잔액은 그대로다")
    void shouldRejectAmountGreaterThanBalanceWithoutChangingBalance() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(500L);

        assertThatThrownBy(() -> wallet.debit(501L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualTo(500L);
    }

    @Test
    @DisplayName("충전 금액은 양수여야 한다")
    void shouldRequirePositiveChargeAmount() {
        PointWallet wallet = PointWallet.create(member());

        assertThatThrownBy(() -> wallet.credit(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사용 금액은 양수여야 한다")
    void shouldRequirePositiveUseAmount() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(1000L);

        assertThatThrownBy(() -> wallet.debit(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
