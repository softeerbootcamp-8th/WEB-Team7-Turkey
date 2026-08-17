package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.AddressRequest;
import com.turkey.quick.order.dto.ContactRequest;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.DeliveryCreateResponse;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송요청 생성 + 포인트 차감을 <b>실제 MySQL</b>에 붙여 검증한다(#37, #40).
 *
 * <p>서비스 단위 테스트가 볼 수 없는 것만 본다 — 전부 DB 가 판정하는 것들이다:
 *
 * <ul>
 *   <li><b>상위 트랜잭션 롤백</b>: 포인트가 모자라면 앞서 저장한 주문·스냅샷까지 사라지는가.
 *       두 이슈 비고의 "하나라도 실패하면 전체 롤백"이 실제로 성립하는지는 여기서만 증명된다.
 *   <li><b>진행 중 1건 제한</b>: {@code uk_delivery_active_customer}(생성 컬럼 UNIQUE)가
 *       두 번째 주문을 막는가. 앱에서 세지 않고 제약에 맡겼으므로 확인이 필요하다.
 *   <li><b>멱등 재전송</b>: 같은 요청키로 두 번 불러도 주문 1건·원장 1건·잔액 1회 차감인가.
 *   <li><b>원장 정합</b>: {@code ck_point_transaction_balance}(before/after/amount)를 통과하는가.
 * </ul>
 *
 * <p>테스트 메서드에 {@code @Transactional} 을 붙이지 않는다. 붙이면 서비스의 트랜잭션이 테스트
 * 트랜잭션에 참여해 <b>롤백 여부를 관찰할 수 없다</b> — 정리는 {@code DatabaseCleaner} 가 한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class DeliveryCreateIntegrationTest extends IntegrationTestSupport {

    private static final AddressRequest PICKUP = new AddressRequest(
            "서울 강남구 테헤란로 152", "5층", "06236",
            new BigDecimal("37.5006"), new BigDecimal("127.0366"));

    private static final AddressRequest DESTINATION = new AddressRequest(
            "서울 강남구 강남대로 396", "1층", "06232",
            new BigDecimal("37.4979"), new BigDecimal("127.0276"));

    @Autowired
    private DeliveryService deliveryService;

    /**
     * 생성은 이 파사드를 거친다 — 컨트롤러와 같은 진입점이다. {@code DeliveryService.createDelivery}
     * 를 직접 부르면 유니크 위반이 {@code DataIntegrityViolationException} 그대로 올라온다(409 로
     * 바꾸는 것도, 동시 재전송을 복구하는 것도 이 파사드의 몫이다).
     */
    @Autowired
    private DeliveryOrderCreator deliveryOrderCreator;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private PointTransactionRepository pointTransactionRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long customerId;

    @BeforeEach
    void setUpFarePolicy() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FarePolicy policy = FarePolicy.create(
                    "v1", 3_000L, 100, 130L, 30_000, LocalDateTime.now().minusDays(1));
            policy.addSurcharge(ItemType.DOCUMENT, 1_000L);
            policy.activate();
            farePolicyRepository.save(policy);
        });
    }

    /** 회원과 지갑을 한 트랜잭션에서 만든다(PointWallet 은 @MapsId 라 member 가 managed 여야 한다). */
    private Long saveCustomerWithBalance(long balance) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member customer = memberRepository.save(Member.create(
                    "int_cust_" + suffix, "hash", "홍길동", "010" + suffix, MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            if (balance > 0) {
                wallet.credit(balance);
            }
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    /** 서버가 이 좌표·물품에 대해 산정하는 요금. 기대값을 하드코딩하지 않기 위해 매번 구한다. */
    private long serverFare() {
        return deliveryService.quoteFare(
                new FareQuoteRequest(ItemType.DOCUMENT, PICKUP, DESTINATION)).fare().totalFare();
    }

    private DeliveryCreateRequest request(String requestKey, long estimatedFare) {
        return new DeliveryCreateRequest(
                requestKey, estimatedFare, ItemType.DOCUMENT, PICKUP, DESTINATION,
                new ContactRequest("김고객", "010-1234-5678"),
                new ContactRequest("이수령", "010-9876-5432"));
    }

    private long balanceOf(Long memberId) {
        return pointWalletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("주문·운임 스냅샷·포인트 차감·원장이 한 트랜잭션에서 함께 저장된다")
    void createsOrderSnapshotAndLedgerTogether() {
        long fare = serverFare();
        customerId = saveCustomerWithBalance(50_000L);
        String requestKey = UUID.randomUUID().toString();

        DeliveryCreateResponse response =
                deliveryOrderCreator.create(request(requestKey, fare), customerId);

        assertThat(response.status()).isEqualTo(OrderStatus.WAITING);
        assertThat(response.deliveryId()).isNotNull();

        DeliveryOrder saved = deliveryOrderRepository.findById(response.deliveryId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.WAITING);
        // 거리는 클라이언트가 보내지 않는다 — 좌표에서 서버가 채운 값이어야 한다
        assertThat(saved.getStraightDistanceMeters()).isPositive();

        assertThat(orderFareSnapshotRepository
                .findByOrder_IdAndFareType(response.deliveryId(), FareType.ESTIMATE))
                .isPresent()
                .get()
                .extracting("totalFare").isEqualTo(fare);

        assertThat(balanceOf(customerId)).isEqualTo(50_000L - fare);

        List<PointTransaction> ledger = pointTransactionRepository.findAll();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getTransactionType()).isEqualTo(PointTransactionType.ORDER_USE);
        // ck_point_transaction_balance 를 통과했다는 것 자체가 before/after/amount 정합의 증거다
        assertThat(ledger.get(0).getBalanceBefore()).isEqualTo(50_000L);
        assertThat(ledger.get(0).getBalanceAfter()).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("포인트가 모자라면 402 이고 주문과 스냅샷까지 롤백된다")
    void rollsBackOrderWhenPointsAreInsufficient() {
        long fare = serverFare();
        customerId = saveCustomerWithBalance(fare - 1);
        String requestKey = UUID.randomUUID().toString();

        Throwable thrown = catchThrowable(
                () -> deliveryOrderCreator.create(request(requestKey, fare), customerId));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);

        // 이 세 줄이 이 이슈의 핵심이다 — 주문만 남고 결제가 안 된 상태가 만들어지면 안 된다
        assertThat(deliveryOrderRepository.findAll()).isEmpty();
        assertThat(orderFareSnapshotRepository.findAll()).isEmpty();
        assertThat(balanceOf(customerId)).isEqualTo(fare - 1);
    }

    @Test
    @DisplayName("진행 중 배송요청이 있으면 두 번째 주문은 409 이고 포인트도 차감되지 않는다")
    void rejectsSecondOrderWhileOneIsInProgress() {
        long fare = serverFare();
        customerId = saveCustomerWithBalance(50_000L);
        deliveryOrderCreator.create(request(UUID.randomUUID().toString(), fare), customerId);
        long balanceAfterFirst = balanceOf(customerId);

        Throwable thrown = catchThrowable(() -> deliveryOrderCreator.create(
                request(UUID.randomUUID().toString(), fare), customerId));

        // uk_delivery_active_customer(생성 컬럼 UNIQUE)가 판정한다 — 앱에서 세지 않는다
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliveryOrderRepository.findAll()).hasSize(1);
        assertThat(balanceOf(customerId)).isEqualTo(balanceAfterFirst);
    }

    @Test
    @DisplayName("같은 요청키로 재전송해도 주문과 차감은 한 번만 일어난다")
    void resendChargesOnlyOnce() {
        long fare = serverFare();
        customerId = saveCustomerWithBalance(50_000L);
        String requestKey = UUID.randomUUID().toString();

        DeliveryCreateResponse first =
                deliveryOrderCreator.create(request(requestKey, fare), customerId);
        DeliveryCreateResponse second =
                deliveryOrderCreator.create(request(requestKey, fare), customerId);

        assertThat(second.deliveryId()).isEqualTo(first.deliveryId());
        assertThat(deliveryOrderRepository.findAll()).hasSize(1);
        assertThat(pointTransactionRepository.findAll()).hasSize(1);
        assertThat(balanceOf(customerId)).isEqualTo(50_000L - fare);
    }

    @Test
    @DisplayName("재전송 응답의 요금은 다시 계산하지 않고 저장된 스냅샷 값을 돌려준다")
    void resendReturnsStoredSnapshotFare() {
        long fare = serverFare();
        customerId = saveCustomerWithBalance(50_000L);
        String requestKey = UUID.randomUUID().toString();
        deliveryOrderCreator.create(request(requestKey, fare), customerId);

        // 요금 정책을 갈아엎는다: 이후 재계산하면 다른 금액이 나온다.
        //
        // 두 트랜잭션으로 나눈다. 한 트랜잭션에 두면 Hibernate 가 INSERT 를 UPDATE 보다 먼저
        // 내보내서(액션 순서가 고정돼 있다) 새 ACTIVE 정책이 들어가는 순간 기존 정책이 아직
        // ACTIVE 라 uk_fare_policy_active 를 위반한다. flush() 를 끼워도 되지만, 순서에 기대는
        // 코드보다 커밋 경계로 못 박는 편이 읽기 쉽다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                farePolicyRepository.findAll().get(0).deactivate());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FarePolicy renewed = FarePolicy.create(
                    "v2", 9_000L, 100, 500L, 30_000, LocalDateTime.now().minusDays(1));
            renewed.activate();
            farePolicyRepository.save(renewed);
        });

        DeliveryCreateResponse resent =
                deliveryOrderCreator.create(request(requestKey, fare), customerId);

        // 실제 청구된 금액과 다른 값을 돌려주면 화면이 사용자에게 거짓말을 하게 된다
        assertThat(resent.estimatedFare().totalFare()).isEqualTo(fare);
        assertThat(resent.estimatedFare().policyVersion()).isEqualTo("v1");
        assertThat(balanceOf(customerId)).isEqualTo(50_000L - fare);
    }
}
