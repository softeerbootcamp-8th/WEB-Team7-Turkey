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
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
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

    private record Fixture(Long riderId, Long orderId, String loginId, OperatingStatus status) {
    }
}
