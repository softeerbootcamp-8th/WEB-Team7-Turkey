package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 라이더 포인트 잔액 조회(#67) 단위 테스트.
 *
 * <p>스프링 없이 리포지토리만 대체한다. 이 층에서 잡을 것은 두 가지뿐이다 — 지갑 값을 그대로
 * 옮기는가, 그리고 지갑이 없을 때 400 이 아니라 500 으로 갈리는가(계약 결정, 단계 2에서 확인).
 * 인증·인터셉터 등록은 여기서 재현되지 않으므로 E2E 가 맡는다.
 */
class RiderPaymentServiceTest {

    private static final Long RIDER_ID = 7L;

    private PointWalletRepository pointWalletRepository;
    private RiderPaymentService riderPaymentService;

    @BeforeEach
    void setUp() {
        pointWalletRepository = mock(PointWalletRepository.class);
        var riderProfileRepository =
                mock(com.turkey.quick.rider.repository.RiderProfileRepository.class);
        var riderWithdrawalRepository =
                mock(com.turkey.quick.rider.repository.RiderWithdrawalRepository.class);
        var pointTransactionRepository =
                mock(com.turkey.quick.payment.repository.PointTransactionRepository.class);
        riderPaymentService = new RiderPaymentService(pointWalletRepository,
                riderProfileRepository,
                riderWithdrawalRepository,
                pointTransactionRepository,
                mock(PayoutGateway.class),
                mock(WithdrawalProcessor.class),
                // 목이 아니라 실물이다 — 목이면 신청 로직이 통째로 안 돈다. 트랜잭션 경계는
                // 프록시가 만드는 것이라 단위 테스트에서는 어차피 없다(그건 통합 테스트 몫).
                new WithdrawalRequester(pointWalletRepository, riderProfileRepository,
                        riderWithdrawalRepository, pointTransactionRepository));
    }

    private PointWallet walletWith(long balance) {
        Member rider = Member.create("rider01", "hash", "김라이더", "01077778888", MemberRole.RIDER);
        PointWallet wallet = PointWallet.create(rider);
        if (balance > 0) {
            wallet.credit(balance);
        }
        return wallet;
    }

    @Test
    @DisplayName("지갑이 있으면 저장된 잔액을 그대로 반환한다")
    void shouldReturnStoredWalletBalance() {
        when(pointWalletRepository.findByMemberId(RIDER_ID)).thenReturn(Optional.of(walletWith(48_000L)));

        PointBalanceResponse response = riderPaymentService.getPointBalance(RIDER_ID);

        assertThat(response.balance()).isEqualTo(48_000L);
    }

    @Test
    @DisplayName("정산 이력이 없는 신규 라이더는 잔액 0 을 받는다")
    void shouldReturnZeroForFreshRider() {
        // 지갑은 회원가입 트랜잭션에서 잔액 0 으로 생성된다(RiderSignupService).
        // 즉 "정산 전"은 지갑 없음이 아니라 잔액 0 이고, 아래 500 케이스와 구분되어야 한다.
        when(pointWalletRepository.findByMemberId(RIDER_ID)).thenReturn(Optional.of(walletWith(0L)));

        assertThat(riderPaymentService.getPointBalance(RIDER_ID).balance()).isZero();
    }

    @Test
    @DisplayName("지갑이 없으면 정합성 오류로 보고 500 을 낸다")
    void shouldFailWithServerErrorWhenWalletMissing() {
        when(pointWalletRepository.findByMemberId(RIDER_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> riderPaymentService.getPointBalance(RIDER_ID));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
