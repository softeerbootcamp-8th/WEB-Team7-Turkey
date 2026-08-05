package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.turkey.quick.order.dto.DeliveryCancelResponse;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송요청 취소(#47)를 실제 MySQL에 붙여 검증한다. 서비스 단위 테스트가 못 보는 것만 본다 —
 * 취소+환급의 트랜잭션 원자성, 소유권 제약, 배차 확정과의 동시성 경쟁.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class DeliveryCancelIntegrationTest extends IntegrationTestSupport {

    private static final AddressRequest PICKUP = new AddressRequest(
            "서울 강남구 테헤란로 152", "5층", "06236",
            new BigDecimal("37.5006"), new BigDecimal("127.0366"));

    private static final AddressRequest DESTINATION = new AddressRequest(
            "서울 강남구 강남대로 396", "1층", "06232",
            new BigDecimal("37.4979"), new BigDecimal("127.0276"));

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
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
                    "cancel_cust_" + suffix, "hash", "홍길동", "010" + suffix, MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            wallet.credit(balance);
            pointWalletRepository.save(wallet);
            return customer.getId();
        });
    }

    private RiderProfile saveAvailableRider() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member member = memberRepository.save(Member.create(
                    "cancel_rider_" + suffix, "hash", "라이더", "011" + suffix, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            profile.goOnline();
            return riderProfileRepository.save(profile);
        });
    }

    private long serverFare() {
        return deliveryService.quoteFare(
                new FareQuoteRequest(ItemType.DOCUMENT, PICKUP, DESTINATION)).fare().totalFare();
    }

    private Long createWaitingOrder(Long customerId, long fare) {
        DeliveryCreateRequest request = new DeliveryCreateRequest(
                UUID.randomUUID().toString(), fare, ItemType.DOCUMENT, PICKUP, DESTINATION,
                new ContactRequest("김고객", "010-1234-5678"),
                new ContactRequest("이수령", "010-9876-5432"));
        return deliveryService.createDelivery(request, customerId).deliveryId();
    }

    private long balanceOf(Long memberId) {
        return pointWalletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("WAITING 주문을 취소하면 전액 환급되고 CANCELED로 바뀐다")
    void cancelsWaitingOrderAndRefundsInFull() {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
        long balanceAfterCharge = balanceOf(customerId);

        DeliveryCancelResponse response = deliveryService.cancelDelivery(orderId, customerId, "단순 변심");

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.canceledAt()).isNotNull();

        DeliveryOrder persisted = deliveryOrderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(persisted.getCancelReason()).isEqualTo("단순 변심");
        assertThat(balanceOf(customerId)).isEqualTo(50_000L);
        assertThat(balanceAfterCharge).isLessThan(50_000L); // 차감이 실제로 있었다는 전제 확인
    }

    @Test
    @DisplayName("이미 취소된 주문을 다시 취소해도 잔액이 두 번 늘어나지 않는다")
    void doesNotDoubleRefundOnRepeatedCancel() {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);

        deliveryService.cancelDelivery(orderId, customerId, "1차 취소");
        long balanceAfterFirstCancel = balanceOf(customerId);
        DeliveryCancelResponse second = deliveryService.cancelDelivery(orderId, customerId, "2차 취소 시도");

        assertThat(second.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(balanceOf(customerId)).isEqualTo(balanceAfterFirstCancel);
        assertThat(pointTransactionRepository.findAll()).hasSize(2); // ORDER_USE 1건 + ORDER_REFUND 1건뿐
    }

    @Test
    @DisplayName("이미 배차된 주문은 취소할 수 없고(409) 잔액도 그대로다")
    void rejectsCancelWhenAlreadyAssigned() {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
        long balanceAfterCharge = balanceOf(customerId);

        RiderProfile rider = saveAvailableRider();
        riderDeliveryRequestService.acceptDeliveryRequest(
                new AuthenticatedRider(rider.getMemberId(), "rider", "라이더", OperatingStatus.AVAILABLE),
                orderId);

        assertThatThrownBy(() -> deliveryService.cancelDelivery(orderId, customerId, "취소 시도"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(deliveryOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.ASSIGNED);
        assertThat(balanceOf(customerId)).isEqualTo(balanceAfterCharge);
    }

    @Test
    @DisplayName("타인의 주문은 취소할 수 없다(404)")
    void rejectsCancelForOtherCustomersOrder() {
        long fare = serverFare();
        Long ownerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(ownerId, fare);
        Long strangerId = saveCustomerWithBalance(50_000L);

        assertThatThrownBy(() -> deliveryService.cancelDelivery(orderId, strangerId, "남의 주문"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(deliveryOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.WAITING);
    }

    @Test
    @DisplayName("배차 확정과 고객 취소가 동시에 실행되면 정확히 하나만 성공한다")
    void exactlyOneWinsWhenAcceptAndCancelRace() throws Exception {
        long fare = serverFare();
        Long customerId = saveCustomerWithBalance(50_000L);
        Long orderId = createWaitingOrder(customerId, fare);
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
                    deliveryService.cancelDelivery(orderId, customerId, "동시 취소 시도");
                    cancelSucceeded.set(true);
                } catch (BusinessException ignored) {
                    // 경쟁 패배는 정상 흐름이다
                } catch (Exception ignored) {
                    // no-op
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        // 성공 조건(#47): 정확히 하나만 성공한다 — 부분 성공(둘 다 성공/둘 다 실패)이 없어야 한다
        assertThat(acceptSucceeded.get()).isNotEqualTo(cancelSucceeded.get());

        DeliveryOrder persisted = deliveryOrderRepository.findById(orderId).orElseThrow();
        if (acceptSucceeded.get()) {
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
            assertThat(balanceOf(customerId)).isLessThan(50_000L); // 환급 안 됨
        } else {
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(balanceOf(customerId)).isEqualTo(50_000L); // 전액 환급됨
        }
    }
}
