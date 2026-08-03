package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.DeliveryProofRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.payment.repository.RiderSettlementRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired RiderDeliveryService riderDeliveryService;
    @Autowired MemberRepository memberRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired DeliveryOrderRepository deliveryOrderRepository;
    @Autowired FarePolicyRepository farePolicyRepository;
    @Autowired OrderFareSnapshotRepository orderFareSnapshotRepository;
    @Autowired DeliveryProofRepository deliveryProofRepository;
    @Autowired RiderSettlementRepository riderSettlementRepository;
    @Autowired PointWalletRepository pointWalletRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("배정된 BUSY 라이더가 출발하면 주문 상태와 이동 시작 시각만 변경한다")
    void shouldStartMovingToPickupAndKeepRiderBusy() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_58_a", "01058000001");

        var response = riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP);

        assertThat(response.status()).isEqualTo(OrderStatus.MOVING_TO_PICKUP);
        assertThat(response.steps()).extracting(step -> step.status())
                .containsExactly(OrderStatus.WAITING, OrderStatus.ASSIGNED, OrderStatus.MOVING_TO_PICKUP);
        DeliveryOrder persisted = deliveryOrderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(persisted.getMovingToPickupAt()).isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("픽업지로 이동 중인 BUSY 라이더가 픽업을 완료하면 시각을 기록하고 BUSY를 유지한다")
    void shouldPickUpAndKeepRiderBusy() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_59_a", "01059000001");
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP);

        var response = riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.PICK_UP);

        assertThat(response.status()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(response.steps()).extracting(step -> step.status())
                .containsExactly(OrderStatus.WAITING, OrderStatus.ASSIGNED,
                        OrderStatus.MOVING_TO_PICKUP, OrderStatus.PICKED_UP);
        DeliveryOrder persisted = deliveryOrderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(persisted.getPickedUpAt()).isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("동일한 픽업 완료 요청을 다시 보내면 409로 거부한다")
    void shouldRejectDuplicatePickUp() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_59_b", "01059000002");
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP);
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(), RiderDeliveryAction.PICK_UP);

        assertThatThrownBy(() -> riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.PICK_UP))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("다른 라이더가 픽업 완료를 요청하면 403으로 거부한다")
    void shouldRejectPickUpByDifferentRider() {
        Fixture assigned = saveAssignedOrder(true, "integration_rider_59_c", "01059000003");
        riderDeliveryService.transition(authenticated(assigned), assigned.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP);
        RiderProfile other = saveRider("integration_rider_59_d", "01059000004", true);
        AuthenticatedRider otherRider = new AuthenticatedRider(
                other.getMemberId(), "integration_rider_59_d", "라이더", OperatingStatus.BUSY);

        assertThatThrownBy(() -> riderDeliveryService.transition(otherRider, assigned.orderId(),
                RiderDeliveryAction.PICK_UP))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("픽업 완료 주문의 배송을 시작하면 DELIVERING 시각을 기록하고 BUSY를 유지한다")
    void shouldStartDeliveringAndKeepRiderBusy() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_65_a", "01065000001");
        moveToPickedUp(fixture);

        var response = riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_DELIVERING);

        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERING);
        DeliveryOrder persisted = deliveryOrderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(persisted.getDeliveringAt()).isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("동일한 배송 시작 요청을 다시 보내면 409로 거부한다")
    void shouldRejectDuplicateStartDelivering() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_65_b", "01065000002");
        moveToPickedUp(fixture);
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_DELIVERING);

        assertThatThrownBy(() -> riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_DELIVERING))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("완료 인증과 함께 배송을 완료하면 라이더를 해제하고 정산 포인트를 적립한다")
    void shouldCompleteDeliveryAndSettle() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_62_a", "01062000001");
        moveToDelivering(fixture);

        var response = riderDeliveryService.complete(authenticated(fixture), fixture.orderId(),
                new RiderDeliveryCompleteRequest(ProofType.PHOTO, "proof/62-a.jpg"));

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(response.settlementAmount()).isEqualTo(3100L);
        DeliveryOrder persisted = deliveryOrderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(persisted.getCompletedAt()).isNotNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(deliveryProofRepository.existsByOrder_Id(fixture.orderId())).isTrue();
        assertThat(riderSettlementRepository.existsByOrder_Id(fixture.orderId())).isTrue();
        assertThat(pointWalletRepository.findByMemberId(fixture.riderId()).orElseThrow().getBalance())
                .isEqualTo(3100L);
    }

    @Test
    @DisplayName("완료된 배송을 다시 완료하려 하면 409로 거부하고 정산을 중복 적립하지 않는다")
    void shouldRejectDuplicateCompletion() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_62_b", "01062000002");
        moveToDelivering(fixture);
        RiderDeliveryCompleteRequest request =
                new RiderDeliveryCompleteRequest(ProofType.RECIPIENT_CONFIRMATION, "recipient-62-b");
        riderDeliveryService.complete(authenticated(fixture), fixture.orderId(), request);

        assertThatThrownBy(() -> riderDeliveryService.complete(authenticated(fixture), fixture.orderId(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(pointWalletRepository.findByMemberId(fixture.riderId()).orElseThrow().getBalance())
                .isEqualTo(3100L);
    }

    @Test
    @DisplayName("정산 포인트 적립이 실패하면 배송 완료와 인증 및 라이더 해제를 모두 롤백한다")
    void shouldRollbackCompletionWhenSettlementFails() {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_62_c", "01062000003");
        moveToDelivering(fixture);
        pointWalletRepository.deleteById(fixture.riderId());

        assertThatThrownBy(() -> riderDeliveryService.complete(authenticated(fixture), fixture.orderId(),
                new RiderDeliveryCompleteRequest(ProofType.AUTH_CODE, "auth-62-c")))
                .isInstanceOf(IllegalStateException.class);

        DeliveryOrder persisted = deliveryOrderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.DELIVERING);
        assertThat(persisted.getCompletedAt()).isNull();
        assertThat(riderProfileRepository.findById(fixture.riderId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
        assertThat(deliveryProofRepository.existsByOrder_Id(fixture.orderId())).isFalse();
        assertThat(riderSettlementRepository.existsByOrder_Id(fixture.orderId())).isFalse();
        assertThat(orderFareSnapshotRepository.findByOrder_IdAndFareType(fixture.orderId(), FareType.FINAL))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 라이더가 배정된 주문을 변경하려 하면 403으로 거부한다")
    void shouldRejectDifferentRider() {
        Fixture assigned = saveAssignedOrder(true, "integration_rider_58_b", "01058000002");
        RiderProfile other = saveRider("integration_rider_58_c", "01058000003", true);
        AuthenticatedRider otherRider = new AuthenticatedRider(
                other.getMemberId(), "integration_rider_58_c", "라이더", OperatingStatus.BUSY);

        assertThatThrownBy(() -> riderDeliveryService.transition(otherRider, assigned.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("BUSY가 아닌 라이더의 배송 상태 변경은 403으로 거부한다")
    void shouldRejectRiderWhoIsNotBusy() {
        Fixture fixture = saveAssignedOrder(false, "integration_rider_58_d", "01058000004");

        assertThatThrownBy(() -> riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("동일한 픽업지 이동 시작 요청이 동시에 오면 정확히 하나만 성공한다")
    void shouldAllowExactlyOneConcurrentTransition() throws Exception {
        Fixture fixture = saveAssignedOrder(true, "integration_rider_58_e", "01058000005");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                                RiderDeliveryAction.START_MOVING_TO_PICKUP);
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getStatus() == HttpStatus.CONFLICT) {
                            conflicted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);
    }

    private Fixture saveAssignedOrder(boolean busy, String loginId, String phoneNumber) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RiderProfile rider = saveRider(loginId, phoneNumber, busy);
            Member customer = memberRepository.save(Member.create(
                    "customer_" + loginId, "hash", "고객", "019" + phoneNumber.substring(3), MemberRole.CUSTOMER));
            DeliveryOrder order = DeliveryOrder.request(customer, "request-" + loginId,
                    ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지", "상세", "12345", new BigDecimal("37.5000000"),
                            new BigDecimal("127.0000000")),
                    Address.of("도착지", "상세", "54321", new BigDecimal("37.6000000"),
                            new BigDecimal("127.1000000")),
                    Contact.of("보내는 사람", "01011112222"), Contact.of("받는 사람", "01033334444"));
            order.assign(rider);
            DeliveryOrder saved = deliveryOrderRepository.save(order);
            FarePolicy policy = farePolicyRepository.save(
                    FarePolicy.create("v58-" + loginId, 3000L, 100, 100L, 30000,
                            LocalDateTime.now().minusDays(1)));
            orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                    saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(), 1000, 3000L, 100L, 0L));
            return new Fixture(rider.getMemberId(), saved.getId(), loginId,
                    busy ? OperatingStatus.BUSY : OperatingStatus.AVAILABLE);
        });
    }

    private RiderProfile saveRider(String loginId, String phoneNumber, boolean busy) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, "hash", "라이더", phoneNumber, MemberRole.RIDER));
            RiderProfile rider = RiderProfile.create(member);
            pointWalletRepository.save(PointWallet.create(member));
            rider.goOnline();
            if (busy) {
                rider.assign();
            }
            return riderProfileRepository.save(rider);
        });
    }

    private AuthenticatedRider authenticated(Fixture fixture) {
        return new AuthenticatedRider(fixture.riderId(), fixture.loginId(), "라이더", fixture.status());
    }

    private void moveToPickedUp(Fixture fixture) {
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_MOVING_TO_PICKUP);
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(), RiderDeliveryAction.PICK_UP);
    }

    private void moveToDelivering(Fixture fixture) {
        moveToPickedUp(fixture);
        riderDeliveryService.transition(authenticated(fixture), fixture.orderId(),
                RiderDeliveryAction.START_DELIVERING);
    }

    private record Fixture(Long riderId, Long orderId, String loginId, OperatingStatus status) {
    }
}
