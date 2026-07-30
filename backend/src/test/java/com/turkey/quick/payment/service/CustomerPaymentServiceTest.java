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
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class CustomerPaymentServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final String REQUEST_KEY = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34";
    private static final String OTHER_REQUEST_KEY = "9a2b7d31-0c44-4f18-8e55-1d3b6a9c0e77";

    private PointWalletRepository pointWalletRepository;
    private PointChargeRepository pointChargeRepository;
    private MemberRepository memberRepository;
    private CustomerPaymentService customerPaymentService;

    @BeforeEach
    void setUp() {
        pointWalletRepository = mock(PointWalletRepository.class);
        pointChargeRepository = mock(PointChargeRepository.class);
        memberRepository = mock(MemberRepository.class);
        customerPaymentService = new CustomerPaymentService(
                pointWalletRepository, pointChargeRepository, memberRepository);
    }

    private Member member() {
        return Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER);
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
}
