package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PaymentMethod;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>알려진 결함을 고정하는 테스트다</b>(#34). 통과가 "올바르게 동작한다"는 뜻이 아니라
 * "아직 이 상태다"라는 뜻이다.
 *
 * <p>재현하는 경쟁: 승인이 PG 응답을 기다리는 <b>동안</b> 취소가 들어와 커밋되는 경우.
 * {@link CustomerPaymentService#confirmPointCharge} 는 잠금 없이 사전 검증한 뒤 PG 를 부르므로
 * 그 사이가 열려 있다. 기존 {@code CustomerPaymentServiceIntegrationTest} 의 동시 요청 테스트는
 * 스레드를 그냥 동시에 띄우는 방식이라 이 순서를 <b>보장하지 못한다</b> — 여기서는 PG 구현체가
 * 승인 응답을 붙잡아 두는 방식으로 순서를 결정론적으로 고정한다.
 *
 * <p>결과적으로 확인하는 것은 두 가지다:
 *
 * <ul>
 *   <li><b>지켜지는 것</b>: 포인트와 상태는 취소가 이긴 그대로다. 잔액이 늘지 않고 원장도 생기지 않는다.
 *       부분 성공이 없다는 뜻이고, 이건 의도한 동작이다.
 *   <li><b>깨지는 것</b>: <b>승인 식별자가 DB 에 남지 않는다.</b> 취소가 이미 커밋돼 상태가 PENDING 이
 *       아니므로 {@link PointChargeApprover#recordApprovalReceived} 가 아무것도 하지 않는다.
 *       그래서 {@code status='PENDING' AND provider_payment_key IS NOT NULL} 이라는 대사 대상 추출
 *       조건에도 걸리지 않는다 — 실 PG 라면 <b>돈은 나갔는데 아무 흔적도 없는 건</b>이 된다.
 * </ul>
 *
 * <p>모의 PG 는 돈이 움직이지 않아 지금은 무해하다. 닫으려면 PG 호출 <b>전에</b> 상태를 선점해야
 * 하고(claim 패턴), 상태 값을 늘리는 것만으로는 경쟁 창이 그대로다 — 검토 내용은 워크로그
 * {@code docs/worklog/2026-07-31-34-point-charge-cancel.md} 부록.
 *
 * <p><b>이 테스트가 실패하면 결함이 고쳐진 것이므로 지우지 말고 기대값을 뒤집는다.</b>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class PointChargeCancelRaceIntegrationTest extends IntegrationTestSupport {

    private static final String REQUEST_KEY = "9d2e4c71-0a83-4f16-bb54-7c1e93af220d";
    private static final long AMOUNT = 30_000L;
    private static final long INITIAL_BALANCE = 5_000L;
    private static final String RACE_PAYMENT_KEY = "mock_pay_race_winner";

    @Autowired
    private CustomerPaymentService customerPaymentService;

    @Autowired
    private BlockingPaymentGateway paymentGateway;

    @Autowired
    private PointChargeRepository pointChargeRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private PointTransactionRepository pointTransactionRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class BlockingGatewayConfig {

        @Bean
        @Primary
        BlockingPaymentGateway blockingPaymentGateway() {
            return new BlockingPaymentGateway();
        }
    }

    /**
     * 승인 응답을 <b>붙잡아 두는</b> PG. {@code confirm} 이 "승인이 났다"를 알린 뒤 신호를 받을 때까지
     * 반환하지 않는다. 실 PG 로 치면 "돈은 이미 빠져나갔는데 우리 서버는 아직 응답을 못 받은" 구간이다.
     */
    static class BlockingPaymentGateway implements PaymentGateway {

        /** PG 가 승인을 확정한 순간. 이 시점 이후로는 실 PG 라면 돈이 나간 상태다. */
        final CountDownLatch approvalIssued = new CountDownLatch(1);

        /** 테스트가 취소를 커밋한 뒤 승인 응답을 흘려보내는 신호. */
        final CountDownLatch releaseApproval = new CountDownLatch(1);

        @Override
        public Approval confirm(ConfirmCommand command) {
            approvalIssued.countDown();
            try {
                if (!releaseApproval.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트가 승인 응답을 풀어 주지 않았다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new Approval(RACE_PAYMENT_KEY, command.expectedAmount(), "MOCK", "모의결제",
                    LocalDateTime.now(ZoneOffset.UTC));
        }

        @Override
        public Preparation prepare(PrepareCommand command) {
            throw new UnsupportedOperationException("이 테스트는 준비 단계를 쓰지 않는다");
        }

        @Override
        public Cancellation cancel(CancelCommand command) {
            throw new UnsupportedOperationException("승인 전 취소는 PG 를 부르지 않는다");
        }
    }

    /** 회원과 지갑을 한 트랜잭션에서 만든다(PointWallet 은 @MapsId 라 member 가 managed 여야 한다). */
    private Long saveCustomerWithWallet() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member customer = memberRepository.save(
                    Member.create("cust_race", "hash", "김고객", "01055556666", MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            wallet.credit(INITIAL_BALANCE);
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    @Test
    @DisplayName("PG 승인 직후 취소가 끼어들면 포인트·상태는 그대로지만 승인 식별자가 남지 않는다")
    void cancelWinningAfterGatewayApprovalLosesTheApprovalKey() throws Exception {
        Long customerId = saveCustomerWithWallet();
        Long pointChargeId = customerPaymentService
                .chargePointRequest(new PointChargeRequest(REQUEST_KEY, AMOUNT, PaymentMethod.CARD),
                        customerId)
                .pointChargeId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Throwable> confirmOutcome = pool.submit(() -> catchThrowable(
                () -> customerPaymentService.confirmPointCharge(
                        pointChargeId,
                        new PointChargeConfirmRequest(AMOUNT, "mock_auth_race"),
                        customerId)));

        // ① PG 가 승인을 확정할 때까지 기다린다. 실 PG 라면 여기서 이미 돈이 나갔다.
        assertThat(paymentGateway.approvalIssued.await(10, TimeUnit.SECONDS))
                .as("PG 승인이 시작되지 않았다")
                .isTrue();

        // ② 그 사이에 취소가 들어와 커밋된다. 승인은 아직 응답을 받지 못한 상태다.
        customerPaymentService.cancelPointCharge(pointChargeId, customerId);

        // ③ 이제 승인 응답을 흘려보낸다.
        paymentGateway.releaseApproval.countDown();

        Throwable confirmFailure = confirmOutcome.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 승인은 확정 구간에서 상태를 다시 확인하고 409 로 막힌다. 부분 성공은 없다.
        assertThat(confirmFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) confirmFailure).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        PointCharge charge = pointChargeRepository.findById(pointChargeId).orElseThrow();

        // 지켜지는 것: 취소가 이긴 그대로다. 돈이 나갔어도 포인트는 늘지 않는다.
        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.CANCELED);
        assertThat(charge.getApprovedAmount()).isNull();
        assertThat(pointWalletRepository.findByMemberId(customerId).orElseThrow().getBalance())
                .isEqualTo(INITIAL_BALANCE);
        assertThat(pointTransactionRepository.findAll()).isEmpty();

        // 깨지는 것: 승인 식별자가 어디에도 남지 않는다.
        // recordApprovalReceived 는 상태가 PENDING 이 아니라 아무것도 하지 않았고,
        // 그래서 이 건은 status='PENDING' AND provider_payment_key IS NOT NULL 대사 조건에도
        // 걸리지 않는다. 실 PG 였다면 여기가 추적 불가능한 손실이다.
        assertThat(charge.getProviderPaymentKey())
                .as("이 단언이 실패했다면 결함이 고쳐진 것이다 — 기대값을 %s 로 뒤집을 것",
                        RACE_PAYMENT_KEY)
                .isNull();
    }
}
