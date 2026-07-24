package com.turkey.quick.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.Test;

class PointWalletTest {

    private Member member() {
        return Member.create("cust01", "hash", "김고객", "01033334444", MemberRole.CUSTOMER);
    }

    @Test
    void 지갑_생성시_잔액은_0이다() {
        PointWallet wallet = PointWallet.create(member());

        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void 충전하면_잔액이_증가한다() {
        PointWallet wallet = PointWallet.create(member());

        wallet.credit(1000L);

        assertThat(wallet.getBalance()).isEqualTo(1000L);
    }

    @Test
    void 사용하면_잔액이_감소한다() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(1000L);

        wallet.debit(300L);

        assertThat(wallet.getBalance()).isEqualTo(700L);
    }

    @Test
    void 잔액보다_큰_금액은_사용할수_없고_잔액은_그대로다() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(500L);

        assertThatThrownBy(() -> wallet.debit(501L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualTo(500L);
    }

    @Test
    void 충전_금액은_양수여야_한다() {
        PointWallet wallet = PointWallet.create(member());

        assertThatThrownBy(() -> wallet.credit(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사용_금액은_양수여야_한다() {
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(1000L);

        assertThatThrownBy(() -> wallet.debit(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
