package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
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
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.rider.service.RiderDeliveryRequestService;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배차 대기 시간 초과 자동 취소(#42)를 실제 MySQL 에 붙여 검증한다.
 *
 * <p>단위 테스트가 볼 수 없는 것만 본다 — 조건부 UPDATE 가 실제로 경쟁을 가르는지, 취소·환급이
 * 하나의 트랜잭션으로 커밋되는지, {@code active_customer_id} UNIQUE 가 지연 만료로 실제로 풀리는지.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class DeliveryTimeoutServiceIntegrationTest extends IntegrationTestSupport {

    private static final AddressRequest PICKUP = new AddressRequest(
            "서울 강남구 테헤란로 152", "5층", "06236",
            new BigDecimal("37.5006"), new BigDecimal("127.0366"));

    private static final AddressRequest DESTINATION = new AddressRequest(
            "서울 강남구 강남대로 396", "1층", "06232",
            new BigDecimal("37.4979"), new BigDecimal("127.0276"));

    @Autowired
    private DeliveryTimeoutService deliveryTimeoutService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private RiderDeliveryRequestService riderDeliveryRequestService;

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
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private Long saveCustomerWithBalance(long balance) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member customer = memberRepository.save(Member.create(
                    "timeout_cust_" + suffix, "hash", "홍길동", "010" + suffix, MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            if (balance > 0) {
                wallet.credit(balance);
            }
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    private RiderProfile saveAvailableRider() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member member = memberRepository.save(Member.create(
                    "timeout_rider_" + suffix, "hash", "라이더", "011" + suffix, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            profile.goOnline();
            return riderProfileRepository.save(profile);
        });
    }

    private long serverFare() {
        return deliveryService.quoteFare(
                new FareQuoteRequest(ItemType.DOCUMENT, PICKUP, DESTINATION)).fare().totalFare();
    }

    private DeliveryCreateRequest request(long estimatedFare) {
        return new DeliveryCreateRequest(
                UUID.randomUUID().toString(), estimatedFare, ItemType.DOCUMENT, PICKUP, DESTINATION,
                new ContactRequest("김고객", "010-1234-5678"),
                new ContactRequest("이수령", "010-9876-5432"));
    }

    private Long createWaitingOrder(Long customerId, long fare) {
        DeliveryCreateResponse response = deliveryService.createDelivery(request(fare), customerId);
        return response.deliveryId();
    }

    /** requestedAt 은 updatable=false 라 도메인 메서드로 못 바꾼다 — 타임아웃 재현을 위해 직접 되민다. */
    private void backdateRequestedAt(Long orderId, LocalDateTime requestedAt) {
        jdbcTemplate.update("UPDATE delivery_order SET requested_at = ? WHERE order_id = ?",
                requestedAt, orderId);
    }

    private long balanceOf(Long memberId) {
        return pointWalletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("타임아웃을 넘긴 WAITING 주문을 취소하고 차감했던 만큼 전액 환급한다")
    void cancelsAndFullyRefundsExpiredWaitingOrder() {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
        long balanceAfterCharge = balanceOf(customerId);
        backdateRequestedAt(orderId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(110));

        boolean result = deliveryTimeoutService.cancelAndRefund(orderId, customerId);

        assertThat(result).isTrue();
        DeliveryOrder persisted = deliveryOrderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(persisted.getCancelReason()).contains("시간을 초과");
        assertThat(balanceOf(customerId)).isEqualTo(50_000L);

        List<PointTransaction> ledger = pointTransactionRepository.findAll();
        PointTransaction refund = ledger.stream()
                .filter(t -> t.getTransactionType() == PointTransactionType.ORDER_REFUND)
                .findFirst().orElseThrow();
        assertThat(refund.getAmount()).isEqualTo(fare);
        assertThat(refund.getBalanceBefore()).isEqualTo(balanceAfterCharge);
        assertThat(refund.getBalanceAfter()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("이미 배차된 주문은 자동 취소·환급 대상에서 제외된다")
    void skipsOrderThatIsAlreadyAssigned() {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
        long balanceAfterCharge = balanceOf(customerId);

        // 아직 만료되지 않은 상태에서 먼저 정상적으로 배차를 확정한다(#42가 수락 시점에도 만료를
        // 검사하므로, 배차 전에 미리 되밀면 수락 자체가 먼저 취소해버려 이 테스트의 전제가 깨진다).
        RiderProfile rider = saveAvailableRider();
        riderDeliveryRequestService.acceptDeliveryRequest(
                new AuthenticatedRider(rider.getMemberId(), "rider", "라이더", OperatingStatus.AVAILABLE),
                orderId);
        backdateRequestedAt(orderId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(110));

        boolean result = deliveryTimeoutService.cancelAndRefund(orderId, customerId);

        assertThat(result).isFalse();
        DeliveryOrder persisted = deliveryOrderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(balanceOf(customerId)).isEqualTo(balanceAfterCharge);
        assertThat(pointTransactionRepository.findAll())
                .extracting(PointTransaction::getTransactionType)
                .doesNotContain(PointTransactionType.ORDER_REFUND);
    }

    @Test
    @DisplayName("스캐너 조회는 타임아웃 넘긴 WAITING 주문만 찾고, 최근 주문·배차된 주문은 제외한다")
    void findsOnlyExpiredWaitingOrdersForScanner() {
        long fare = serverFare();

        Long expiredCustomerId = saveCustomerWithBalance(50_000L);
        Long expiredOrderId = createWaitingOrder(expiredCustomerId, fare);
        backdateRequestedAt(expiredOrderId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(110));

        Long freshCustomerId = saveCustomerWithBalance(50_000L);
        Long freshOrderId = createWaitingOrder(freshCustomerId, fare);
        // 방금 생성됐으니 backdate 하지 않는다 — 아직 타임아웃 전이어야 한다.

        Long assignedCustomerId = saveCustomerWithBalance(50_000L);
        Long assignedOrderId = createWaitingOrder(assignedCustomerId, fare);
        RiderProfile rider = saveAvailableRider();
        riderDeliveryRequestService.acceptDeliveryRequest(
                new AuthenticatedRider(rider.getMemberId(), "rider", "라이더", OperatingStatus.AVAILABLE),
                assignedOrderId);
        backdateRequestedAt(assignedOrderId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(110));

        LocalDateTime cutoff = DeliveryTimeoutService.cutoff(LocalDateTime.now(ZoneOffset.UTC));
        List<DeliveryOrder> found = deliveryOrderRepository
                .findByStatusAndRequestedAtBefore(OrderStatus.WAITING, cutoff);

        assertThat(found).extracting(DeliveryOrder::getId)
                .contains(expiredOrderId)
                .doesNotContain(freshOrderId, assignedOrderId);
    }

    // 지연 만료: "만료된 기존 주문이 있으면 새 주문 생성 시 자동 정리된다"는 이제 만료 트리거가
    // 서비스가 아니라 컨트롤러(CustomerDeliveryController)에 있다(#463 — 커넥션 풀 교착 회피로
    // expireIfStale 를 createDelivery 트랜잭션 밖으로 이동). 서비스를 직접 부르면 만료 정리가 걸리지
    // 않으므로 이 시나리오는 실제 HTTP → 컨트롤러 배선을 타는 CustomerDeliveryCreateE2ETest 로 옮겼다.

    @Test
    @DisplayName("배차 확정과 자동 취소가 동시에 실행되면 정확히 하나만 성공한다")
    void exactlyOneWinsWhenAcceptAndAutoCancelRace() throws Exception {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
        backdateRequestedAt(orderId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(110));
        RiderProfile rider = saveAvailableRider();

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var acceptSucceeded = new AtomicBoolean(false);
        var cancelSucceeded = new AtomicBoolean(false);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                try {
                    start.await();
                    riderDeliveryRequestService.acceptDeliveryRequest(
                            new AuthenticatedRider(rider.getMemberId(), "rider", "라이더", OperatingStatus.AVAILABLE),
                            orderId);
                    acceptSucceeded.set(true);
                } catch (BusinessException ignored) {
                    // 경쟁 패배는 정상 흐름이다
                } catch (Exception ignored) {
                    // no-op
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    cancelSucceeded.set(deliveryTimeoutService.cancelAndRefund(orderId, customerId));
                } catch (Exception ignored) {
                    // no-op
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        // 성공 조건(#42): 정확히 하나만 성공한다 — 부분 성공(둘 다 성공/둘 다 실패)이 없어야 한다
        assertThat(acceptSucceeded.get()).isNotEqualTo(cancelSucceeded.get());

        DeliveryOrder persisted = deliveryOrderRepository.findById(orderId).orElseThrow();
        if (acceptSucceeded.get()) {
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
            assertThat(balanceOf(customerId)).isEqualTo(50_000L - fare);
            assertThat(riderProfileRepository.findById(rider.getMemberId()).orElseThrow().getOperatingStatus())
                    .isEqualTo(OperatingStatus.BUSY);
        } else {
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(balanceOf(customerId)).isEqualTo(50_000L);
            assertThat(riderProfileRepository.findById(rider.getMemberId()).orElseThrow().getOperatingStatus())
                    .isEqualTo(OperatingStatus.AVAILABLE);
        }
    }
}
