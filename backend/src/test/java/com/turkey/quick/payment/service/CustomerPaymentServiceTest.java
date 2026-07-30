package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PaymentMethod;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerPaymentServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final String REQUEST_KEY = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34";
    private static final String OTHER_REQUEST_KEY = "9a2b7d31-0c44-4f18-8e55-1d3b6a9c0e77";

    private PointWalletRepository pointWalletRepository;
    private PointChargeRepository pointChargeRepository;
    private PointTransactionRepository pointTransactionRepository;
    private MemberRepository memberRepository;
    private PaymentGateway paymentGateway;
    private CustomerPaymentService customerPaymentService;

    @BeforeEach
    void setUp() {
        pointWalletRepository = mock(PointWalletRepository.class);
        pointChargeRepository = mock(PointChargeRepository.class);
        pointTransactionRepository = mock(PointTransactionRepository.class);
        memberRepository = mock(MemberRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        customerPaymentService = new CustomerPaymentService(
                pointWalletRepository, pointChargeRepository, pointTransactionRepository,
                memberRepository, paymentGateway);
    }

    private Member member() {
        return Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER);
    }

    /**
     * 식별자가 있는 회원. 승인 로직이 {@code charge.getCustomer().getId()} 로 소유를 확인하는데
     * {@code Member.create} 는 auto increment PK 를 채우지 않아 그대로 쓰면 NPE 가 난다.
     * 엔터티에 setter 를 열지 않기 위해 테스트에서만 리플렉션으로 주입한다.
     */
    private Member memberWithId(Long memberId) {
        Member member = member();
        ReflectionTestUtils.setField(member, "id", memberId);
        return member;
    }

    private PointChargeRequest request(long amount) {
        return new PointChargeRequest(REQUEST_KEY, amount, PaymentMethod.CARD);
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

    /**
     * 포인트 충전 준비(#32).
     *
     * <p>멱등성은 "같은 호출을 두 번 해도 상태가 한 번만 변한다"는 성질이므로, 리포지토리가 기존 건을
     * 돌려주도록 미리 스텁해 두고 한 번만 호출하는 테스트로는 증명되지 않는다(그건 분기 검증이다).
     * 그래서 리포지토리를 <b>(고객, 멱등키) 유니크 저장소를 흉내 낸 인메모리 페이크</b>로 만들고
     * 서비스를 실제로 두 번 호출한다.
     *
     * <p>다만 이 층에서 증명되는 것은 <b>서비스의 판단</b>까지다. 두 요청이 진짜 동시에 들어왔을 때
     * 한 건만 남는 보장은 DB 의 {@code uk_point_charge_customer_request} 가 하는 일이므로 통합
     * 테스트에서 확인해야 한다.
     */
    @Nested
    @DisplayName("포인트 충전 준비(#32)")
    class ChargePointRequest {

        /** 멱등키 → 저장된 충전 건. 페이크가 "저장한 것을 다음 조회에서 돌려주는" 최소 상태를 갖게 한다. */
        private final Map<String, PointCharge> store = new HashMap<>();

        @BeforeEach
        void fakePointChargeStore() {
            when(memberRepository.getReferenceById(CUSTOMER_ID)).thenReturn(member());
            when(pointChargeRepository.findByCustomer_IdAndChargeRequestKey(eq(CUSTOMER_ID), anyString()))
                    .thenAnswer(invocation ->
                            Optional.ofNullable(store.get((String) invocation.getArgument(1))));
            when(pointChargeRepository.saveAndFlush(any(PointCharge.class)))
                    .thenAnswer(invocation -> {
                        PointCharge saved = invocation.getArgument(0);
                        store.put(saved.getChargeRequestKey(), saved);
                        return saved;
                    });
        }

        @Test
        @DisplayName("유효한 금액이면 PENDING 상태의 충전 요청을 만든다")
        void createsPendingCharge() {
            // when: 30,000원 충전을 요청하면
            PointChargeResponse response =
                    customerPaymentService.chargePointRequest(request(30_000L), CUSTOMER_ID);

            // then: PENDING 으로 저장되고 요청 금액·결제수단이 그대로 담긴다
            // (식별자·요청시각은 DB 가 채우는 값이라 통합 테스트에서 확인한다)
            assertThat(response.status()).isEqualTo(PointChargeStatus.PENDING);
            assertThat(response.requestedAmount()).isEqualTo(30_000L);
            assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CARD);
            verify(pointChargeRepository).saveAndFlush(any(PointCharge.class));
        }

        @Test
        @DisplayName("충전 준비 단계에서는 지갑을 건드리지 않는다")
        void doesNotTouchWallet() {
            // when: 충전을 요청하면
            customerPaymentService.chargePointRequest(request(10_000L), CUSTOMER_ID);

            // then: 잔액은 승인 단계에서 변하므로 지갑 조회조차 일어나지 않는다
            verifyNoInteractions(pointWalletRepository);
        }

        @Test
        @DisplayName("같은 멱등키로 두 번 요청해도 충전 건은 한 건만 남고 같은 결과를 돌려준다")
        void sameKeyTwiceCreatesSingleCharge() {
            // given: 클라이언트가 재전송 시에도 유지하는 같은 요청
            PointChargeRequest retriedRequest = request(30_000L);

            // when: 응답 유실 등으로 같은 요청이 두 번 처리되면
            PointChargeResponse first =
                    customerPaymentService.chargePointRequest(retriedRequest, CUSTOMER_ID);
            PointChargeResponse second =
                    customerPaymentService.chargePointRequest(retriedRequest, CUSTOMER_ID);

            // then: 두 응답이 같고, 저장은 첫 번째 호출에서 한 번만 일어난다
            assertThat(second).isEqualTo(first);
            assertThat(store).hasSize(1);
            verify(pointChargeRepository, times(1)).saveAndFlush(any(PointCharge.class));
        }

        @Test
        @DisplayName("멱등키가 다르면 별도의 충전 건이 만들어진다")
        void differentKeyCreatesAnotherCharge() {
            // given: 같은 금액이지만 키가 다른 두 요청(사용자가 충전을 두 번 시도한 경우)
            PointChargeRequest firstAttempt = request(30_000L);
            PointChargeRequest secondAttempt =
                    new PointChargeRequest(OTHER_REQUEST_KEY, 30_000L, PaymentMethod.CARD);

            // when: 두 요청을 처리하면
            customerPaymentService.chargePointRequest(firstAttempt, CUSTOMER_ID);
            customerPaymentService.chargePointRequest(secondAttempt, CUSTOMER_ID);

            // then: 충전 건이 두 건 만들어진다
            // (위 테스트의 "한 건"이 페이크가 전부 뭉개서 나온 결과가 아님을 이 테스트가 보증한다)
            assertThat(store).hasSize(2);
            verify(pointChargeRepository, times(2)).saveAndFlush(any(PointCharge.class));
        }

        @Test
        @DisplayName("동시에 같은 멱등키로 저장되면 유니크 위반을 409 로 바꿔 거부한다")
        void rejectsConcurrentDuplicate() {
            // given: 경쟁 요청이 먼저 커밋해 유니크 제약을 위반하는 상황
            when(pointChargeRepository.saveAndFlush(any(PointCharge.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_point_charge_customer_request"));

            // when & then: 부분 성공 없이 409 로 실패한다
            Throwable thrown = catchThrowable(() ->
                    customerPaymentService.chargePointRequest(request(30_000L), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("허용 범위를 넘는 금액이면 충전을 시작하지 않는다")
        void rejectsAmountOutOfRange() {
            // when & then: 상한(1,000,000원)을 넘으면 저장 전에 거부된다
            assertThatThrownBy(() ->
                    customerPaymentService.chargePointRequest(request(1_001_000L), CUSTOMER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(pointChargeRepository, never()).saveAndFlush(any(PointCharge.class));
        }

        @Test
        @DisplayName("하한 미달 금액이면 충전을 시작하지 않는다")
        void rejectsAmountBelowMinimum() {
            // when & then: 최소 충전 금액(1,000원) 미달
            assertThatThrownBy(() ->
                    customerPaymentService.chargePointRequest(request(500L), CUSTOMER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("금액 단위가 맞지 않으면 충전을 시작하지 않는다")
        void rejectsAmountNotOnUnit() {
            // when & then: 1,000원 단위가 아닌 금액
            assertThatThrownBy(() ->
                    customerPaymentService.chargePointRequest(request(10_500L), CUSTOMER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(pointChargeRepository, never()).saveAndFlush(any(PointCharge.class));
        }
    }

    /**
     * 포인트 충전 모의 승인(#33).
     *
     * <p>이 층에서 보는 것은 <b>서비스의 판단</b>이다: 소유·금액·상태 검증 순서, 멱등 재승인,
     * 실패 결과 처리, 그리고 성공 시 세 변경(상태·잔액·원장)이 모두 일어나는지.
     *
     * <p>동시 승인에서 정말 한 번만 증가하는지는 행 잠금이 하는 일이라 목으로 재현할 수 없고
     * {@link CustomerPaymentServiceIntegrationTest} 가 검증한다.
     */
    @Nested
    @DisplayName("포인트 충전 모의 승인(#33)")
    class ConfirmPointCharge {

        private static final Long CHARGE_ID = 331L;
        private static final long AMOUNT = 30_000L;

        private PointCharge pendingCharge() {
            PointCharge charge = PointCharge.request(
                    memberWithId(CUSTOMER_ID), REQUEST_KEY, PaymentMethod.CARD, AMOUNT, "MOCK");
            ReflectionTestUtils.setField(charge, "id", CHARGE_ID);
            return charge;
        }

        private PointChargeConfirmRequest confirmRequest(long amount) {
            return new PointChargeConfirmRequest(amount, "mock_auth_test");
        }

        /** PG 가 승인해 준 상황. 승인 식별자·카드사 정보는 게이트웨이 응답에서 온다. */
        private void gatewayApproves() {
            when(paymentGateway.confirm(any(PaymentGateway.ConfirmCommand.class)))
                    .thenAnswer(invocation -> {
                        PaymentGateway.ConfirmCommand command = invocation.getArgument(0);
                        return new PaymentGateway.Approval("mock_pay_approved",
                                command.expectedAmount(), "MOCK", "모의결제",
                                LocalDateTime.now(ZoneOffset.UTC));
                    });
        }

        private void gatewayFails(PaymentGateway.PaymentGatewayException.FailureType type) {
            when(paymentGateway.confirm(any(PaymentGateway.ConfirmCommand.class)))
                    .thenThrow(new PaymentGateway.PaymentGatewayException(
                            type, "MOCK_" + type, "모의 결제 실패"));
        }

        private PointWallet walletWith(long balance) {
            PointWallet wallet = PointWallet.create(memberWithId(CUSTOMER_ID));
            if (balance > 0) {
                wallet.credit(balance);
            }
            return wallet;
        }

        @Test
        @DisplayName("PG 가 승인하면 상태·잔액·원장이 함께 바뀐다")
        void approvesAndCreditsWallet() {
            // given: PENDING 충전 30,000원과 잔액 5,000원인 지갑
            PointCharge charge = pendingCharge();
            PointWallet wallet = walletWith(5_000L);
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));
            when(pointWalletRepository.findByMemberIdForUpdate(CUSTOMER_ID))
                    .thenReturn(Optional.of(wallet));
            gatewayApproves();

            // when: 승인을 요청하면
            PointChargeConfirmResponse response = customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            // then: PAID 로 전이하고 잔액이 승인 금액만큼 늘고 CHARGE 원장이 1행 남는다
            assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PAID);
            assertThat(charge.getApprovedAmount()).isEqualTo(AMOUNT);
            assertThat(wallet.getBalance()).isEqualTo(35_000L);
            assertThat(response.balanceAfter()).isEqualTo(35_000L);
            assertThat(response.approvedAt()).isNotNull();

            // 승인 식별자·카드사 정보는 PG 응답에서 온다(서버가 만들지 않는다)
            assertThat(charge.getProviderPaymentKey()).isEqualTo("mock_pay_approved");
            assertThat(charge.getIssuerCode()).isEqualTo("MOCK");

            ArgumentCaptor<PointTransaction> ledger = ArgumentCaptor.forClass(PointTransaction.class);
            verify(pointTransactionRepository).save(ledger.capture());
            assertThat(ledger.getValue().getTransactionType()).isEqualTo(PointTransactionType.CHARGE);
            assertThat(ledger.getValue().getBalanceBefore()).isEqualTo(5_000L);
            assertThat(ledger.getValue().getBalanceAfter()).isEqualTo(35_000L);
        }

        @Test
        @DisplayName("PG 에 넘기는 승인 금액은 요청값이 아니라 DB 의 요청 금액이다")
        void sendsStoredAmountToGateway() {
            PointCharge charge = pendingCharge();
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));
            when(pointWalletRepository.findByMemberIdForUpdate(CUSTOMER_ID))
                    .thenReturn(Optional.of(walletWith(0L)));
            gatewayApproves();

            customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            ArgumentCaptor<PaymentGateway.ConfirmCommand> command =
                    ArgumentCaptor.forClass(PaymentGateway.ConfirmCommand.class);
            verify(paymentGateway).confirm(command.capture());
            assertThat(command.getValue().expectedAmount()).isEqualTo(AMOUNT);
            assertThat(command.getValue().chargeRequestKey()).isEqualTo(REQUEST_KEY);
            assertThat(command.getValue().authToken()).isEqualTo("mock_auth_test");
        }

        @Test
        @DisplayName("이미 승인된 충전에 다시 승인 요청이 오면 잔액을 늘리지 않고 그때 결과를 돌려준다")
        void replayReturnsOriginalApproval() {
            // given: 이미 승인돼 잔액이 35,000원이 된 충전과 그때의 원장
            PointCharge charge = pendingCharge();
            charge.approve("mock_pay_first", null, null);
            PointWallet wallet = walletWith(35_000L);
            PointTransaction ledger = PointTransaction.forCharge(
                    wallet, PointTransactionType.CHARGE, AMOUNT, 5_000L, "ledger-key", charge);
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));
            when(pointTransactionRepository.findByPointCharge_IdAndTransactionType(
                    CHARGE_ID, PointTransactionType.CHARGE)).thenReturn(Optional.of(ledger));

            // when: 같은 승인 요청이 다시 오면
            PointChargeConfirmResponse response = customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            // then: 잔액은 그대로고 원장도 새로 쓰지 않는다. 잔액은 승인 당시 값(35,000)이다.
            // PG 도 다시 부르지 않는다 — 이미 승인된 결제를 또 승인 요청하면 안 된다
            assertThat(response.status()).isEqualTo(PointChargeStatus.PAID);
            assertThat(response.balanceAfter()).isEqualTo(35_000L);
            assertThat(wallet.getBalance()).isEqualTo(35_000L);
            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
            verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
            verify(pointWalletRepository, never()).findByMemberIdForUpdate(any());
        }

        @Test
        @DisplayName("PG 가 거절하면 충전하지 않고 FAILED 로 확정한다")
        void declineMarksChargeFailedWithoutCrediting() {
            // given: PENDING 충전과 잔액 5,000원, 그리고 거절하는 PG
            PointCharge charge = pendingCharge();
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));
            when(pointWalletRepository.findByMemberId(CUSTOMER_ID))
                    .thenReturn(Optional.of(walletWith(5_000L)));
            gatewayFails(PaymentGateway.PaymentGatewayException.FailureType.DECLINED);

            // when: 승인을 요청하면
            PointChargeConfirmResponse response = customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            // then: FAILED 로 확정되고 잔액·원장은 건드리지 않는다(예외가 아니라 정상 응답이다 —
            // 예외면 트랜잭션이 롤백되어 FAILED 전이가 사라진다)
            assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.FAILED);
            assertThat(charge.getFailureReason()).contains("MOCK_DECLINED");
            assertThat(response.status()).isEqualTo(PointChargeStatus.FAILED);
            assertThat(response.approvedAmount()).isZero();
            assertThat(response.balanceAfter()).isEqualTo(5_000L);
            assertThat(response.approvedAt()).isNull();
            verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
            verify(pointWalletRepository, never()).findByMemberIdForUpdate(any());
        }

        @Test
        @DisplayName("PG 응답을 받지 못하면 상태를 확정하지 않고 502 로 알린다")
        void timeoutKeepsChargePending() {
            // given: 응답이 오지 않은 상황 — 결제가 됐는지 알 수 없다
            PointCharge charge = pendingCharge();
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));
            gatewayFails(PaymentGateway.PaymentGatewayException.FailureType.TIMEOUT);

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            // then: FAILED 로 못 박지 않는다. 실제로 결제된 건을 실패로 확정하면 되돌릴 수 없다
            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PENDING);
            verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
        }

        @Test
        @DisplayName("결제 금액이 요청 금액과 다르면 400 으로 거부한다")
        void rejectsAmountMismatch() {
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID))
                    .thenReturn(Optional.of(pendingCharge()));

            // when & then: 30,000원 요청에 100원을 승인시키려는 시도
            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(100L), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
        }

        @Test
        @DisplayName("남의 충전 건은 403 으로 거부한다")
        void rejectsOtherCustomersCharge() {
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID))
                    .thenReturn(Optional.of(pendingCharge()));

            // when & then: 다른 고객(99)이 같은 충전 건을 승인시키려는 시도
            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), 99L));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("존재하지 않는 충전 건은 404 로 거부한다")
        void rejectsMissingCharge() {
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("PENDING·PAID 가 아닌 상태는 409 로 거부한다")
        void rejectsNonPendingStatus() {
            // given: 이미 실패로 확정된 충전
            PointCharge charge = pendingCharge();
            charge.fail("앞선 모의 결제 실패");
            when(pointChargeRepository.findByIdForUpdate(CHARGE_ID)).thenReturn(Optional.of(charge));

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
        }
    }
}
