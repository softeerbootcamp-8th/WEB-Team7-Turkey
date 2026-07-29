package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerPaymentServiceTest {

    private static final Long CUSTOMER_ID = 1L;

    private PointWalletRepository pointWalletRepository;
    private CustomerPaymentService customerPaymentService;

    @BeforeEach
    void setUp() {
        pointWalletRepository = mock(PointWalletRepository.class);
        customerPaymentService = new CustomerPaymentService(pointWalletRepository);
    }

    private Member member() {
        return Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER);
    }

    @Test
    @DisplayName("지갑이 있는 고객이면 지갑에 저장된 잔액을 그대로 반환한다")
    void returnsBalance() {
        // given: 잔액 12,500원이 적립된 지갑을 가진 고객
        PointWallet wallet = PointWallet.create(member());
        wallet.credit(12_500L);
        when(pointWalletRepository.findByMemberId(CUSTOMER_ID)).thenReturn(Optional.of(wallet));

        // when: 잔액을 조회하면
        PointBalanceResponse response = customerPaymentService.getPointBalance(CUSTOMER_ID);

        // then: 지갑에 저장된 잔액과 갱신 시각이 그대로 반환된다
        assertThat(response.balance()).isEqualTo(12_500L);
        assertThat(response.updatedAt()).isEqualTo(wallet.getUpdatedAt());
    }

    @Test
    @DisplayName("지갑이 없는 고객이면 데이터 정합성 오류로 처리한다")
    void throwsWhenWalletMissing() {
        // given: 지갑이 없는 고객
        when(pointWalletRepository.findByMemberId(CUSTOMER_ID)).thenReturn(Optional.empty());

        // when & then: 잔액을 조회하면 예외가 발생한다
        assertThatThrownBy(() -> customerPaymentService.getPointBalance(CUSTOMER_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
