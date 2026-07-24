package com.turkey.quick.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RiderPayoutAccountTest {

    private RiderProfile rider() {
        Member member = Member.create("rider01", "hash", "홍라이더", "01077778888", MemberRole.RIDER);
        return RiderProfile.create(member);
    }

    private byte[] cipher(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 출금계좌_등록시_입력값이_보관된다() {
        RiderProfile rider = rider();
        byte[] ciphertext = cipher("enc-110-123456");

        RiderPayoutAccount account = RiderPayoutAccount.register(
                rider, "088", ciphertext, "신한(**3456)", "홍라이더");

        assertThat(account.getRider()).isSameAs(rider);
        assertThat(account.getBankCode()).isEqualTo("088");
        assertThat(account.getAccountNumberCiphertext()).isEqualTo(ciphertext);
        assertThat(account.getMaskedAccountNumber()).isEqualTo("신한(**3456)");
        assertThat(account.getAccountHolderName()).isEqualTo("홍라이더");
    }

    @Test
    void 계좌_변경시_은행_암호문_마스킹_예금주가_교체된다() {
        RiderPayoutAccount account = RiderPayoutAccount.register(
                rider(), "088", cipher("enc-110-123456"), "신한(**3456)", "홍라이더");
        byte[] newCipher = cipher("enc-020-987654");

        account.changeAccount("020", newCipher, "우리(**7654)", "홍길동");

        assertThat(account.getBankCode()).isEqualTo("020");
        assertThat(account.getAccountNumberCiphertext()).isEqualTo(newCipher);
        assertThat(account.getMaskedAccountNumber()).isEqualTo("우리(**7654)");
        assertThat(account.getAccountHolderName()).isEqualTo("홍길동");
    }
}
