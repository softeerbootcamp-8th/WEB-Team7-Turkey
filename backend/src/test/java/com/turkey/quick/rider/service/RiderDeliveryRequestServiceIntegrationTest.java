package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private Member rider;
    private FarePolicy farePolicy;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RIDER_GEO_KEY);
        rider = memberRepository.save(
                Member.create("integration_rider01", "hash", "라이더", "01099998888", MemberRole.RIDER));
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
     */
    private RiderProfile saveRiderProfile(String loginId, String phoneNumber) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, "hash", "다른라이더", phoneNumber, MemberRole.RIDER));
            return riderProfileRepository.save(RiderProfile.create(member));
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

    @Test
    @DisplayName("WAITING 주문만 반환하고, 이미 배차된(ASSIGNED) 주문은 제외한다")
    void shouldReturnOnlyWaitingOrders() {
        DeliveryOrder waiting = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        DeliveryOrder assignedTarget = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));
        RiderProfile assignedRiderProfile = saveRiderProfile("integration_rider02", "01077776666");
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
}
