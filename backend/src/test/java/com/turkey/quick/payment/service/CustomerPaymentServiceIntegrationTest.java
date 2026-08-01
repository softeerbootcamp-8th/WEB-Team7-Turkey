package com.turkey.quick.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PaymentMethod;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포인트 충전 준비의 <b>DB 가 개입하는 정합성</b>을 검증한다(#32).
 *
 * <p>단위 테스트({@link CustomerPaymentServiceTest})는 리포지토리를 인메모리 페이크로 바꿔 서비스의
 * 판단만 본다. 그래서 이 두 가지를 증명할 수 없다:
 *
 * <ul>
 *   <li>같은 멱등키가 <b>진짜 동시에</b> 들어왔을 때 정말 한 건만 남는지
 *       — 이것은 {@code uk_point_charge_customer_request} 가 하는 일이다.
 *   <li>{@code @PrePersist} 로 채워지는 {@code requestedAt} 과 auto increment {@code pointChargeId} 가
 *       실제로 채워지는지 — 목은 이 값을 null 로 둔다.
 * </ul>
 *
 * <p>동시성 테스트는 {@code @Transactional} 을 붙이지 않는다. 붙이면 두 스레드가 테스트 트랜잭션을
 * 공유하지 않아 어차피 의미가 없고, 각 스레드가 진짜로 커밋해야 유니크 위반이 재현된다. 남는 행은
 * {@link IntegrationTestSupport} 가 다음 테스트 전에 TRUNCATE 로 정리한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerPaymentServiceIntegrationTest extends IntegrationTestSupport {

    private static final String REQUEST_KEY = "3f1c8ab2-55d4-4e0a-9c77-2b6e1f40aa91";
    private static final long AMOUNT = 30_000L;

    @Autowired
    private CustomerPaymentService customerPaymentService;

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

    private Long saveCustomer() {
        Member customer = memberRepository.save(
                Member.create("cust_charge", "hash", "김고객", "01055556666", MemberRole.CUSTOMER));
        return customer.getId();
    }

    /**
     * 승인 검증에는 지갑이 있어야 한다. 실제 서비스에서는 회원가입이 지갑을 함께 만들지만(#237),
     * 이 테스트는 회원을 직접 저장하므로 지갑도 같이 만든다.
     *
     * <p><b>한 트랜잭션 안에서 만들어야 한다.</b> {@code PointWallet} 은 {@code @MapsId} 로 member 와
     * PK 를 공유하므로 persist 시점에 연관 Member 가 managed 여야 한다. 리포지토리 호출을 각각 따로
     * 하면(각자 트랜잭션) Member 가 detached 상태라
     * "detached entity passed to persist" 로 실패한다 — {@code CustomerSignupService.signup} 이
     * {@code @Transactional} 안에서 둘을 함께 저장하는 것과 같은 조건을 맞춘 것이다.
     */
    private Long saveCustomerWithWallet(long initialBalance) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member customer = memberRepository.save(
                    Member.create("cust_charge", "hash", "김고객", "01055556666", MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            if (initialBalance > 0) {
                wallet.credit(initialBalance);
            }
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    private PointChargeConfirmRequest confirmRequest() {
        return new PointChargeConfirmRequest(AMOUNT, "mock_auth_integration");
    }

    private PointChargeRequest request() {
        return new PointChargeRequest(REQUEST_KEY, AMOUNT, PaymentMethod.CARD);
    }

    @Test
    @DisplayName("충전 준비는 PENDING 행 하나를 만들고 식별자·요청시각을 DB 에서 채운다")
    void persistsPendingChargeWithGeneratedFields() {
        Long customerId = saveCustomer();

        PointChargeResponse response = customerPaymentService.chargePointRequest(request(), customerId);

        // 목으로는 확인할 수 없던 값들: auto increment PK 와 @PrePersist 의 requestedAt
        assertThat(response.pointChargeId()).isNotNull();
        assertThat(response.requestedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(response.requestedAmount()).isEqualTo(AMOUNT);

        List<PointCharge> saved = pointChargeRepository.findAll();
        assertThat(saved).hasSize(1);
        // 준비 단계는 승인 관련 컬럼을 비워 둔다(ck_point_charge_state_values 가 요구하는 조합).
        assertThat(saved.get(0).getApprovedAmount()).isNull();
        assertThat(saved.get(0).getApprovedAt()).isNull();
        assertThat(saved.get(0).getRefundedAmount()).isZero();
    }

    @Test
    @DisplayName("같은 멱등키로 순차 재전송하면 새 행을 만들지 않고 같은 충전 건을 돌려준다")
    void sequentialRetryReturnsSameCharge() {
        Long customerId = saveCustomer();

        PointChargeResponse first = customerPaymentService.chargePointRequest(request(), customerId);
        PointChargeResponse second = customerPaymentService.chargePointRequest(request(), customerId);

        assertThat(second.pointChargeId()).isEqualTo(first.pointChargeId());
        assertThat(pointChargeRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("같은 멱등키로 동시에 요청하면 한 건만 저장되고 나머지는 명확히 실패한다")
    void concurrentSameKeyLeavesExactlyOneCharge() throws InterruptedException {
        Long customerId = saveCustomer();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    // 조회 시점을 최대한 겹치게 해서 "둘 다 없다고 판단하는" 창을 만든다.
                    startTogether.await();
                    customerPaymentService.chargePointRequest(request(), customerId);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    failureCount.incrementAndGet();
                    unexpected.compareAndSet(null, e);
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 핵심: 몇 개가 성공했느냐가 아니라 행이 하나만 남았느냐다.
        // 경쟁에서 이긴 요청은 성공하고, 나머지는 성공(선조회로 흡수) 또는 명확한 실패로 갈린다.
        // 부분 성공(행이 2개 이상 남거나, 전부 실패)이 없어야 한다.
        assertThat(pointChargeRepository.findAll())
                .as("동시 요청 %d건 중 남은 충전 행 (성공 %d / 실패 %d, 첫 실패=%s)",
                        threads, successCount.get(), failureCount.get(),
                        unexpected.get() == null ? "없음" : unexpected.get().toString())
                .hasSize(1);
        assertThat(successCount.get())
                .as("적어도 한 요청은 성공해야 한다")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("모의 승인은 상태·잔액·원장을 한 트랜잭션에서 함께 반영한다")
    void confirmAppliesAllThreeChanges() {
        Long customerId = saveCustomerWithWallet(5_000L);
        PointChargeResponse prepared =
                customerPaymentService.chargePointRequest(request(), customerId);

        PointChargeConfirmResponse response = customerPaymentService.confirmPointCharge(
                prepared.pointChargeId(), confirmRequest(), customerId);

        assertThat(response.status()).isEqualTo(PointChargeStatus.PAID);
        assertThat(response.approvedAmount()).isEqualTo(AMOUNT);
        assertThat(response.balanceAfter()).isEqualTo(35_000L);
        assertThat(response.approvedAt()).isNotNull();

        // 지갑이 실제로 반영됐는지
        assertThat(pointWalletRepository.findByMemberId(customerId))
                .get()
                .extracting(PointWallet::getBalance)
                .isEqualTo(35_000L);

        // 원장 1행. balance_before/after 조합은 ck_point_transaction_balance 가 검사하므로
        // 여기까지 커밋됐다는 사실 자체가 그 제약을 통과했다는 뜻이다.
        List<PointTransaction> ledger = pointTransactionRepository.findAll();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getTransactionType()).isEqualTo(PointTransactionType.CHARGE);
        assertThat(ledger.get(0).getBalanceBefore()).isEqualTo(5_000L);
        assertThat(ledger.get(0).getBalanceAfter()).isEqualTo(35_000L);
        assertThat(ledger.get(0).getPointCharge().getId()).isEqualTo(prepared.pointChargeId());
    }

    @Test
    @DisplayName("승인 성공 시 승인 식별자가 DB 에 남는다")
    void persistsProviderPaymentKey() {
        Long customerId = saveCustomerWithWallet(0L);
        Long pointChargeId = customerPaymentService
                .chargePointRequest(request(), customerId)
                .pointChargeId();

        customerPaymentService.confirmPointCharge(pointChargeId, confirmRequest(), customerId);

        // 승인 식별자는 PG 응답에서 오고, 반영 실패 시 대사의 근거가 되는 값이다
        assertThat(pointChargeRepository.findById(pointChargeId))
                .get()
                .extracting(PointCharge::getProviderPaymentKey)
                .asString()
                .startsWith("mock_pay_");
    }

    @Test
    @DisplayName("같은 충전 건에 동시에 승인 요청이 와도 포인트는 한 번만 증가한다")
    void concurrentConfirmCreditsOnlyOnce() throws InterruptedException {
        Long customerId = saveCustomerWithWallet(5_000L);
        Long pointChargeId = customerPaymentService
                .chargePointRequest(request(), customerId)
                .pointChargeId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await();
                    customerPaymentService.confirmPointCharge(
                            pointChargeId, confirmRequest(), customerId);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    failureCount.incrementAndGet();
                    unexpected.compareAndSet(null, e);
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        String context = "동시 승인 %d건 (성공 %d / 실패 %d, 첫 실패=%s)".formatted(
                threads, successCount.get(), failureCount.get(),
                unexpected.get() == null ? "없음" : unexpected.get().toString());

        // 핵심: 잔액이 딱 한 번만 늘었는지. 8건이 모두 성공 응답을 받아도(패자는 멱등 응답)
        // 잔액과 원장은 한 번만 변해야 한다.
        assertThat(pointWalletRepository.findByMemberId(customerId))
                .get()
                .extracting(PointWallet::getBalance)
                .as(context)
                .isEqualTo(35_000L);
        assertThat(pointTransactionRepository.findAll()).as(context).hasSize(1);
        assertThat(successCount.get()).as(context).isGreaterThanOrEqualTo(1);
    }
}
