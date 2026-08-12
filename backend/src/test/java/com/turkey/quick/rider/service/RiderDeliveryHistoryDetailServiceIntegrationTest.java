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
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryDetailResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 기록 상세 조회(#71) 통합 테스트. 실제 완료 흐름({@code RiderDeliveryService.complete})으로
 * FINAL 운임 스냅샷·정산·완료 인증을 만든 뒤, 상세 조회가 그 값들을 한 응답으로 조립해 오는지와
 * 소유·상태 게이트(타인 것·없음·미완료를 같은 404)를 실제 MySQL 에서 검증한다.
 *
 * <p>배차 HTTP 경로가 아직 없어({@code TrackingFixture} 와 같은 이유) 상태는 도메인/서비스로 만든다.
 * 목록 조회 통합 테스트와 달리 상세는 스냅샷·정산이 반드시 있어야 해서 완료 흐름을 그대로 밟는다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryHistoryDetailServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired private RiderDeliveryHistoryService riderDeliveryHistoryService;
    @Autowired private RiderDeliveryService riderDeliveryService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RiderProfileRepository riderProfileRepository;
    @Autowired private DeliveryOrderRepository deliveryOrderRepository;
    @Autowired private FarePolicyRepository farePolicyRepository;
    @Autowired private OrderFareSnapshotRepository orderFareSnapshotRepository;
    @Autowired private PointWalletRepository pointWalletRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    @DisplayName("완료한 배송의 상세를 조회하면 주소·물품·거리·확정 운임·정산 내역·타임라인·완료 인증이 함께 온다")
    void returnsFullDetailForOwnCompletedDelivery() {
        Fixture fixture = completedDelivery(ProofType.RECIPIENT_CONFIRMATION, "recipient-71-a");

        RiderDeliveryHistoryDetailResponse detail =
                riderDeliveryHistoryService.getDeliveryHistoryDetail(fixture.riderId(), fixture.orderId());

        assertThat(detail.deliveryId()).isEqualTo(fixture.orderId());
        assertThat(detail.itemType()).isEqualTo(ItemType.SMALL_PARCEL);
        assertThat(detail.pickup().roadAddress()).isEqualTo("픽업지");
        assertThat(detail.destination().roadAddress()).isEqualTo("도착지");
        assertThat(detail.straightDistanceMeters()).isEqualTo(1000);
        // 확정 운임(FINAL 스냅샷)과 정산액은 완료 흐름이 함께 만든 값이라 서로 맞물려야 한다.
        assertThat(detail.finalFare()).isNotNull();
        assertThat(detail.finalFare().totalFare()).isEqualTo(detail.settlementAmount());
        assertThat(detail.settlementAmount()).isPositive();
        assertThat(detail.settledAt()).isNotNull();
        assertThat(detail.steps()).extracting(step -> step.status())
                .containsExactly(OrderStatus.WAITING, OrderStatus.ASSIGNED, OrderStatus.MOVING_TO_PICKUP,
                        OrderStatus.PICKED_UP, OrderStatus.DELIVERING, OrderStatus.COMPLETED);
        assertThat(detail.proofType()).isEqualTo(ProofType.RECIPIENT_CONFIRMATION);
        assertThat(detail.proofValue()).isEqualTo("recipient-71-a");
    }

    @Test
    @DisplayName("다른 라이더가 완료한 배송을 조회하면 404 로 거부한다(존재 여부를 흘리지 않는다)")
    void rejectsOtherRidersDeliveryWith404() {
        Fixture owner = completedDelivery(ProofType.AUTH_CODE, "auth-71-b");
        Long otherRiderId = onlineRider("hist71_other", "01071000009");

        assertThatThrownBy(() ->
                riderDeliveryHistoryService.getDeliveryHistoryDetail(otherRiderId, owner.orderId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("존재하지 않는 주문을 조회하면 404 로 거부한다")
    void rejectsUnknownDeliveryWith404() {
        Long riderId = onlineRider("hist71_unknown", "01071000010");

        assertThatThrownBy(() ->
                riderDeliveryHistoryService.getDeliveryHistoryDetail(riderId, 987654321L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("아직 완료되지 않은 진행 중 배송을 운행 기록 상세로 조회하면 404 로 거부한다")
    void rejectsInProgressDeliveryWith404() {
        Fixture fixture = assignedDelivery();
        moveToDelivering(fixture); // DELIVERING 까지만, 완료하지 않음

        assertThatThrownBy(() ->
                riderDeliveryHistoryService.getDeliveryHistoryDetail(fixture.riderId(), fixture.orderId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Fixture completedDelivery(ProofType proofType, String proofValue) {
        Fixture fixture = assignedDelivery();
        moveToDelivering(fixture);
        riderDeliveryService.complete(authenticated(fixture), fixture.orderId(),
                new RiderDeliveryCompleteRequest(proofType, proofValue));
        return fixture;
    }

    /** 새 고객·라이더로 주문을 배차 확정(배송 ASSIGNED + 라이더 BUSY)까지 만들고 ESTIMATE 스냅샷을 둔다. */
    private Fixture assignedDelivery() {
        int n = sequence.incrementAndGet();
        String loginId = "hist71_rider_" + n;
        String phone = "0107100%04d".formatted(n);
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member riderMember = memberRepository.save(
                    Member.create(loginId, "hash", "홍라이더", phone, MemberRole.RIDER));
            RiderProfile rider = RiderProfile.create(riderMember);
            pointWalletRepository.save(PointWallet.create(riderMember));
            rider.goOnline();
            rider.assign();
            riderProfileRepository.save(rider);

            Member customer = memberRepository.save(Member.create(
                    "hist71_customer_" + n, "hash", "김고객", "0107110%04d".formatted(n), MemberRole.CUSTOMER));
            DeliveryOrder order = DeliveryOrder.request(customer, "request-71-" + n,
                    ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지", "상세", "12345", new BigDecimal("37.5000000"),
                            new BigDecimal("127.0000000")),
                    Address.of("도착지", "상세", "54321", new BigDecimal("37.6000000"),
                            new BigDecimal("127.1000000")),
                    Contact.of("보내는 사람", "01011112222"), Contact.of("받는 사람", "01033334444"));
            order.assign(rider);
            DeliveryOrder saved = deliveryOrderRepository.save(order);

            FarePolicy policy = farePolicyRepository.save(FarePolicy.create(
                    "v71-" + n, 3000L, 100, 100L, 30000, LocalDateTime.now().minusDays(1)));
            orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                    saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(), 1000, 3000L, 100L, 0L));

            return new Fixture(rider.getMemberId(), saved.getId(), loginId);
        });
    }

    private Long onlineRider(String loginId, String phone) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, "hash", "홍라이더", phone, MemberRole.RIDER));
            RiderProfile rider = RiderProfile.create(member);
            rider.goOnline();
            return riderProfileRepository.save(rider).getMemberId();
        });
    }

    private void moveToDelivering(Fixture fixture) {
        AuthenticatedRider auth = authenticated(fixture);
        riderDeliveryService.transition(auth, fixture.orderId(), RiderDeliveryAction.START_MOVING_TO_PICKUP);
        riderDeliveryService.transition(auth, fixture.orderId(), RiderDeliveryAction.PICK_UP);
        riderDeliveryService.transition(auth, fixture.orderId(), RiderDeliveryAction.START_DELIVERING);
    }

    private AuthenticatedRider authenticated(Fixture fixture) {
        return new AuthenticatedRider(fixture.riderId(), fixture.loginId(), "홍라이더", OperatingStatus.BUSY);
    }

    private record Fixture(Long riderId, Long orderId, String loginId) {
    }
}
