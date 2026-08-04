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
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 MySQL(JPA 매핑·조회)과 실제 Redis(GEO) 에 붙여서 검증한다.
 * RIDE-LOC-001(라이더 위치 쓰기)이 아직 진행 중이라, 이 테스트는 그 결과물을 기다리지 않고
 * {@code riders:geo} 에 직접 GEOADD 하여 "쓰기는 이미 되어 있다"고 가정한 상태를 재현한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryRequestServiceIntegrationTest extends IntegrationTestSupport {

    private static final String RIDER_GEO_KEY = "riders:geo";

    @Autowired
    private RiderDeliveryRequestService riderDeliveryRequestService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Member rider;
    private FarePolicy farePolicy;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RIDER_GEO_KEY);
        // #56부터는 rider_profile 행이 실제로 있어야 accept() 의 조건부 UPDATE(operating_status='AVAILABLE')가
        // 의미를 갖는다. saveRiderProfile(available=true) 로 Member+RiderProfile 을 함께 만든다.
        RiderProfile riderProfile = saveRiderProfile("integration_rider01", "01099998888", true);
        // RiderProfile.member 는 지연 로딩이라 트랜잭션 밖에서 접근하면 LazyInitializationException.
        // memberId(=PK, @MapsId)로 다시 조회한다 — 새 트랜잭션이라 지연 로딩 문제가 없다.
        rider = memberRepository.findById(riderProfile.getMemberId()).orElseThrow();
        farePolicy = farePolicyRepository.save(FarePolicy.create(
                "v1", 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(RIDER_GEO_KEY);
    }

    private AuthenticatedRider authenticatedRider(OperatingStatus status) {
        return new AuthenticatedRider(rider.getId(), rider.getLoginId(), rider.getName(), status);
    }

    /**
     * Member 와 RiderProfile 을 한 트랜잭션에서 저장한다({@code RiderSessionE2ETest.saveRider}와 같은 이유).
     * RiderProfile.member 는 @MapsId 라서, 서로 다른 트랜잭션에서 저장하면 두 번째 저장 시점에
     * Member 가 detached 상태로 남아 "detached entity passed to persist" 로 실패한다.
     * available=true 면 저장 전에 goOnline() 을 호출해 AVAILABLE 로 만든다(#56 accept() 검증용).
     */
    private RiderProfile saveRiderProfile(String loginId, String phoneNumber, boolean available) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, "hash", "다른라이더", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (available) {
                profile.goOnline();
            }
            return riderProfileRepository.save(profile);
        });
    }

    private DeliveryOrder saveWaitingOrder(BigDecimal pickupLat, BigDecimal pickupLon) {
        String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
        Member customer = memberRepository.save(
                Member.create("integration_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                        MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-" + System.nanoTime(), ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", pickupLat, pickupLon),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, farePolicy, FareType.ESTIMATE, "v1", 1000, 3000L, 130L, 0L));
        return saved;
    }

    /** OrderFareSnapshot.create(..., 3000L, 130L, 0L) 의 합 — 아래 픽스처와 테스트 기대값이 함께 참조한다. */
    private static final long WALLET_FIXTURE_FARE = 3_130L;

    /**
     * #42 만료 정리 검증용 — 환급이 지갑을 필요로 하므로, 지갑까지 갖춘 주문을 만든다.
     *
     * <p>이 헬퍼는 {@code DeliveryService.createDelivery} 를 거치지 않고 주문을 직접 만들기 때문에
     * 실제 차감이 일어나지 않는다. 그래서 지갑을 {@code balance} 에 미리 {@link #WALLET_FIXTURE_FARE}
     * 만큼 차감된 상태({@code balance - WALLET_FIXTURE_FARE})로 만들어 둔다 — "이미 이 주문 요금을
     * 지불한 상태"를 흉내내는 것이다. 환급 후 잔액이 정확히 {@code balance} 로 돌아오는지가 검증 대상이다.
     */
    private DeliveryOrder saveWaitingOrderWithWallet(BigDecimal pickupLat, BigDecimal pickupLon, long balance) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
            Member customer = memberRepository.save(
                    Member.create("integration_wallet_customer_" + uniqueSuffix, "hash", "고객",
                            "010" + uniqueSuffix, MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            wallet.credit(balance);
            wallet.debit(WALLET_FIXTURE_FARE);
            pointWalletRepository.save(wallet);

            DeliveryOrder order = DeliveryOrder.request(customer, "req-" + System.nanoTime(),
                    ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지 도로명", "상세", "12345", pickupLat, pickupLon),
                    Address.of("도착지 도로명", "상세", "54321",
                            new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                    Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
            DeliveryOrder saved = deliveryOrderRepository.save(order);
            orderFareSnapshotRepository.save(
                    OrderFareSnapshot.create(saved, farePolicy, FareType.ESTIMATE, "v1", 1000, 3000L, 130L, 0L));
            return saved;
        });
    }

    private void backdateRequestedAt(Long orderId, LocalDateTime requestedAt) {
        jdbcTemplate.update("UPDATE delivery_order SET requested_at = ? WHERE order_id = ?",
                requestedAt, orderId);
    }

    @Test
    @DisplayName("WAITING 주문만 반환하고, 이미 배차된(ASSIGNED) 주문은 제외한다")
    void shouldReturnOnlyWaitingOrders() {
        DeliveryOrder waiting = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        DeliveryOrder assignedTarget = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));
        RiderProfile assignedRiderProfile = saveRiderProfile("integration_rider02", "01077776666", false);
        assignedTarget.assign(assignedRiderProfile);
        deliveryOrderRepository.save(assignedTarget);

        List<RiderDeliveryRequestSummaryResponse> result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), 100_000, "REQUESTED_AT");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(waiting.getId())
                .doesNotContain(assignedTarget.getId());
    }

    @Test
    @DisplayName("Redis GEO 에 라이더 위치가 있으면 실제 거리로 반경을 거르고 채운다")
    void shouldFilterAndFillDistanceUsingRealRedisGeo() {
        redisTemplate.opsForGeo().add(RIDER_GEO_KEY, new org.springframework.data.geo.Point(127.0000000, 37.5000000),
                rider.getId().toString());

        DeliveryOrder near = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        DeliveryOrder far = saveWaitingOrder(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"));

        List<RiderDeliveryRequestSummaryResponse> result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), 3000, "DISTANCE");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(near.getId())
                .doesNotContain(far.getId());
        RiderDeliveryRequestSummaryResponse nearSummary = result.stream()
                .filter(r -> r.deliveryId().equals(near.getId())).findFirst().orElseThrow();
        assertThat(nearSummary.distanceToPickupMeters()).isNotNull().isLessThan(3000);
    }

    @Test
    @DisplayName("Redis GEO 에 라이더 위치가 없으면 반경으로 거르지 않고 거리 필드는 null이다")
    void shouldDegradeGracefullyWhenNoRedisPosition() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"));

        List<RiderDeliveryRequestSummaryResponse> result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), 100, "DISTANCE");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId).contains(order.getId());
        assertThat(result.stream().filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow()
                .distanceToPickupMeters()).isNull();
    }

    @Test
    @DisplayName("예상 정산액은 실제 저장된 ESTIMATE 운임 스냅샷의 총 운임과 같다")
    void shouldExposeExpectedSettlementAmountFromFareSnapshot() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        List<RiderDeliveryRequestSummaryResponse> result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), 100_000, "REQUESTED_AT");

        RiderDeliveryRequestSummaryResponse summary = result.stream()
                .filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow();
        assertThat(summary.expectedSettlementAmount()).isEqualTo(3130L);
    }

    @Test
    @DisplayName("[#57] WAITING 주문 상세를 조회하면 상세 주소 없이 반환된다")
    void shouldReturnDetailForWaitingOrder() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        RiderDeliveryRequestDetailResponse result = riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId());

        assertThat(result.deliveryId()).isEqualTo(order.getId());
        assertThat(result.pickup().roadAddress()).isEqualTo("픽업지 도로명");
        assertThat(result.pickup().detailAddress()).isNull();
        assertThat(result.destination().detailAddress()).isNull();
        assertThat(result.estimatedFare().totalFare()).isEqualTo(3130L);
        assertThat(result.estimatedMinutes()).isPositive();
    }

    @Test
    @DisplayName("[#57] 이미 배차된 주문의 상세를 조회하면 404다")
    void shouldRejectDetailForAssignedOrder() {
        DeliveryOrder assignedTarget = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));
        RiderProfile assignedRiderProfile = saveRiderProfile("integration_rider03", "01066665555", false);
        assignedTarget.assign(assignedRiderProfile);
        deliveryOrderRepository.save(assignedTarget);

        assertThatThrownBy(() -> riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), assignedTarget.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("[#57] 존재하지 않는 주문 ID를 조회하면 404다")
    void shouldRejectDetailForNonExistentOrder() {
        assertThatThrownBy(() -> riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), 999_999_999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Member fetchMember(RiderProfile profile) {
        return memberRepository.findById(profile.getMemberId()).orElseThrow();
    }

    @Test
    @DisplayName("[#56] 배차를 확정하면 주문은 ASSIGNED, 라이더는 BUSY로 함께 바뀐다")
    void shouldAssignOrderAndMarkRiderBusyOnSuccess() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        RiderDeliveryRequestAcceptResponse result = riderDeliveryRequestService.acceptDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId());

        assertThat(result.status()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(result.operatingStatus()).isEqualTo(OperatingStatus.BUSY);

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(persisted.getAssignedRider().getMemberId()).isEqualTo(rider.getId());
        RiderProfile persistedProfile = riderProfileRepository.findById(rider.getId()).orElseThrow();
        assertThat(persistedProfile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("[#56] 취소된 주문을 수락하려 하면 409와 취소 사유를 반환하고, 상태는 그대로다")
    void shouldRejectAcceptWhenOrderCanceled() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        order.cancel("고객 취소");
        deliveryOrderRepository.save(order);

        assertThatThrownBy(() -> riderDeliveryRequestService.acceptDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소")
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        RiderProfile persistedProfile = riderProfileRepository.findById(rider.getId()).orElseThrow();
        assertThat(persistedProfile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("[#56, ADR-006] 두 라이더가 같은 주문을 동시에 수락하면 한 명만 성공한다")
    void shouldAssignExactlyOneRiderOnConcurrentAcceptSameOrder() throws Exception {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        Member riderX = rider;
        Member riderY = fetchMember(saveRiderProfile("integration_rider_concurrent_y", "01012340000", true));

        int attempts = 2;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var succeeded = new AtomicInteger();
        var conflicted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (Member candidate : List.of(riderX, riderY)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        riderDeliveryRequestService.acceptDeliveryRequest(
                                new AuthenticatedRider(candidate.getId(), candidate.getLoginId(), candidate.getName(),
                                        OperatingStatus.AVAILABLE),
                                order.getId());
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        conflicted.incrementAndGet();
                    } catch (Exception ignored) {
                        // 경쟁 패배는 위 BusinessException(409)으로만 오는 게 정상이라, 그 외는 잡되 집계하지 않는다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        Long winnerId = persisted.getAssignedRider().getMemberId();
        Long loserId = winnerId.equals(riderX.getId()) ? riderY.getId() : riderX.getId();
        assertThat(riderProfileRepository.findById(winnerId).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
        assertThat(riderProfileRepository.findById(loserId).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("[#56, ADR-006] 한 라이더가 서로 다른 두 주문을 동시에 수락하면 한 건만 배차된다")
    void shouldAssignExactlyOneOrderWhenSameRiderRacesTwoOrders() throws Exception {
        DeliveryOrder orderA = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        DeliveryOrder orderB = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));

        int attempts = 2;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var succeeded = new AtomicInteger();
        var conflicted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (DeliveryOrder target : List.of(orderA, orderB)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        riderDeliveryRequestService.acceptDeliveryRequest(
                                authenticatedRider(OperatingStatus.AVAILABLE), target.getId());
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        conflicted.incrementAndGet();
                    } catch (Exception ignored) {
                        // no-op
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        DeliveryOrder persistedA = deliveryOrderRepository.findById(orderA.getId()).orElseThrow();
        DeliveryOrder persistedB = deliveryOrderRepository.findById(orderB.getId()).orElseThrow();
        long assignedCount = List.of(persistedA, persistedB).stream()
                .filter(o -> o.getStatus() == OrderStatus.ASSIGNED).count();
        assertThat(assignedCount).isEqualTo(1);
        assertThat(riderProfileRepository.findById(rider.getId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("[#42] 배차 대기 타임아웃을 넘긴 주문은 수락 시도만으로 취소·환급되고 409를 받는다")
    void shouldCancelAndRefundExpiredOrderOnAcceptAttempt() {
        DeliveryOrder order = saveWaitingOrderWithWallet(
                new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), 50_000L);
        backdateRequestedAt(order.getId(), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));

        assertThatThrownBy(() -> riderDeliveryRequestService.acceptDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소")
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        // 스캐너가 아직 안 돌았어도, 수락 시도 자체가 만료 정리를 트리거해 전액 환급까지 끝나 있어야 한다
        assertThat(pointWalletRepository.findByMemberId(order.getCustomer().getId()).orElseThrow().getBalance())
                .isEqualTo(50_000L);
        RiderProfile persistedProfile = riderProfileRepository.findById(rider.getId()).orElseThrow();
        assertThat(persistedProfile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }
}
