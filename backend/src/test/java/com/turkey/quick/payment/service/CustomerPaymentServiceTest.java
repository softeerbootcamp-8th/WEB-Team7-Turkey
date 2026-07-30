package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.repository.PointChargeRepository;
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
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerPaymentServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final String REQUEST_KEY = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34";
    private static final String OTHER_REQUEST_KEY = "9a2b7d31-0c44-4f18-8e55-1d3b6a9c0e77";

    private PointWalletRepository pointWalletRepository;
    private PointChargeRepository pointChargeRepository;
    private MemberRepository memberRepository;
    private PaymentGateway paymentGateway;
    private PointChargeApprover pointChargeApprover;
    private CustomerPaymentService customerPaymentService;

    @BeforeEach
    void setUp() {
        pointWalletRepository = mock(PointWalletRepository.class);
        pointChargeRepository = mock(PointChargeRepository.class);
        memberRepository = mock(MemberRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        pointChargeApprover = mock(PointChargeApprover.class);
        customerPaymentService = new CustomerPaymentService(
                pointWalletRepository, pointChargeRepository, memberRepository,
                paymentGateway, pointChargeApprover);
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
     * <p>이 층에서 보는 것은 <b>파사드의 판단과 위임</b>이다: 승인 전 검증, PG 에 무엇을 넘기는지,
     * 실패 유형별로 어디로 가는지, 그리고 성공 시 "승인키 선커밋 → 확정" 순서를 지키는지.
     *
     * <p>상태·잔액·원장이 실제로 함께 바뀌는지와 동시 승인에서 한 번만 증가하는지는
     * {@link PointChargeApprover} 의 트랜잭션이 하는 일이라 목으로 재현할 수 없고
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

        private void chargeExists(PointCharge charge) {
            when(pointChargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
            when(pointWalletRepository.existsById(CUSTOMER_ID)).thenReturn(true);
        }

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

        @Test
        @DisplayName("승인에 성공하면 승인키를 먼저 커밋한 뒤 확정한다")
        void recordsApprovalKeyBeforeFinalizing() {
            chargeExists(pendingCharge());
            gatewayApproves();

            customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            // 순서가 뒤집히면 확정이 실패했을 때 승인 사실이 남지 않는다
            InOrder order = inOrder(pointChargeApprover);
            order.verify(pointChargeApprover).recordApprovalReceived(CHARGE_ID, "mock_pay_approved");
            order.verify(pointChargeApprover)
                    .finalizeApproval(eq(CHARGE_ID), eq(CUSTOMER_ID), any(PaymentGateway.Approval.class));
        }

        @Test
        @DisplayName("PG 에 넘기는 승인 금액은 요청값이 아니라 DB 의 요청 금액이다")
        void sendsStoredAmountToGateway() {
            chargeExists(pendingCharge());
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
        @DisplayName("이미 승인된 충전이면 PG 를 부르지 않고 그때 결과를 돌려준다")
        void replaySkipsGateway() {
            PointCharge charge = pendingCharge();
            charge.approve("mock_pay_first", null, null);
            chargeExists(charge);

            customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
            verify(pointChargeApprover).alreadyApprovedResponse(charge);
            verify(pointChargeApprover, never())
                    .finalizeApproval(any(), any(), any(PaymentGateway.Approval.class));
        }

        @Test
        @DisplayName("PG 가 거절하면 확정으로 가지 않고 실패 확정에 위임한다")
        void declineDelegatesToMarkFailed() {
            chargeExists(pendingCharge());
            gatewayFails(PaymentGateway.PaymentGatewayException.FailureType.DECLINED);

            customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID);

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(pointChargeApprover).markFailed(eq(CHARGE_ID), eq(CUSTOMER_ID), reason.capture());
            assertThat(reason.getValue()).contains("MOCK_DECLINED");
            verify(pointChargeApprover, never()).recordApprovalReceived(any(), anyString());
            verify(pointChargeApprover, never())
                    .finalizeApproval(any(), any(), any(PaymentGateway.Approval.class));
        }

        @Test
        @DisplayName("PG 응답을 받지 못하면 상태를 확정하지 않고 502 로 알린다")
        void timeoutKeepsChargePending() {
            chargeExists(pendingCharge());
            gatewayFails(PaymentGateway.PaymentGatewayException.FailureType.TIMEOUT);

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            // 결제가 됐는지 알 수 없다. FAILED 로 못 박으면 실제로 결제된 건을 실패로 확정하게 된다
            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            verify(pointChargeApprover, never()).markFailed(any(), any(), anyString());
            verify(pointChargeApprover, never()).recordApprovalReceived(any(), anyString());
        }

        @Test
        @DisplayName("승인 식별자가 이미 기록된 PENDING 건은 PG 를 다시 부르지 않고 409 로 막는다")
        void blocksRetryWhenApprovalKeyAlreadyRecorded() {
            // given: 앞선 승인이 반영 도중 끊겨 승인키만 남은 건
            PointCharge charge = pendingCharge();
            charge.markApprovalReceived("mock_pay_orphan");
            chargeExists(charge);

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            // 다시 부르면 새 승인키가 기존 값을 덮어써 추적 근거가 사라진다
            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
        }

        @Test
        @DisplayName("지갑이 없으면 PG 를 부르기 전에 막는다")
        void rejectsMissingWalletBeforeCallingGateway() {
            when(pointChargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(pendingCharge()));
            when(pointWalletRepository.existsById(CUSTOMER_ID)).thenReturn(false);

            assertThatThrownBy(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID))
                    .isInstanceOf(IllegalStateException.class);

            // 승인 뒤에 알면 이미 돈이 나간 뒤다
            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
        }

        @Test
        @DisplayName("결제 금액이 요청 금액과 다르면 400 으로 거부한다")
        void rejectsAmountMismatch() {
            chargeExists(pendingCharge());

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(100L), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
        }

        @Test
        @DisplayName("남의 충전 건은 403 으로 거부한다")
        void rejectsOtherCustomersCharge() {
            chargeExists(pendingCharge());

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), 99L));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("존재하지 않는 충전 건은 404 로 거부한다")
        void rejectsMissingCharge() {
            when(pointChargeRepository.findById(CHARGE_ID)).thenReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("PENDING·PAID 가 아닌 상태는 409 로 거부한다")
        void rejectsNonPendingStatus() {
            PointCharge charge = pendingCharge();
            charge.fail("앞선 모의 결제 실패");
            chargeExists(charge);

            Throwable thrown = catchThrowable(() -> customerPaymentService.confirmPointCharge(
                    CHARGE_ID, confirmRequest(AMOUNT), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            verify(paymentGateway, never()).confirm(any(PaymentGateway.ConfirmCommand.class));
        }
    }
}
