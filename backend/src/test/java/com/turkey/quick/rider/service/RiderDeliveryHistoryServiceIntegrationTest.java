package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryItemResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 기록 목록 조회(#70) 통합 테스트. 파생 쿼리
 * {@code findByAssignedRider_MemberIdAndStatus} 가 실제 MySQL 에서 본인·완료 배송만 최신순으로
 * 걸러 오는지, 페이지 슬라이싱과 전체 건수가 맞는지를 검증한다.
 *
 * <p>완료 배송은 도메인 전이 메서드를 순서대로 밟아 만든다({@code TrackingFixture} 와 같은 이유 —
 * {@code status} setter 가 없고 각 전이가 시각 컬럼·CHECK 제약을 함께 채운다). 같은 라이더로 여러 건을
 * 만들어야 해서 픽스처를 직접 둔다(TrackingFixture 는 시나리오마다 새 라이더를 만든다).
 * 완료 시각은 {@code DATETIME(3)} 이라 같은 밀리초에 여러 건이 완료되면 정렬이 흔들리므로,
 * 완료 사이에 소량 대기를 두어 완료 시각을 구분한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryHistoryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RiderDeliveryHistoryService riderDeliveryHistoryService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("본인에게 배정·완료된 배송만 완료 시각 최신순으로 조회되고, 타인 배송과 진행 중 배송은 제외된다")
    void returnsOnlyOwnCompletedDeliveriesLatestFirst() {
        Long riderId = newRider();
        Long first = completedDeliveryFor(riderId);
        sleepForDistinctCompletedAt();
        Long second = completedDeliveryFor(riderId);
        sleepForDistinctCompletedAt();
        Long third = completedDeliveryFor(riderId);
        Long inProgress = deliveringDeliveryFor(riderId); // 진행 중 → 제외되어야 한다

        Long otherRiderId = newRider();
        Long otherCompleted = completedDeliveryFor(otherRiderId); // 타인 → 제외되어야 한다

        RiderDeliveryHistoryListResponse response = riderDeliveryHistoryService.getDeliveryHistories(riderId, 0, 10);

        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.items())
                .extracting(RiderDeliveryHistoryItemResponse::deliveryId)
                .containsExactly(third, second, first) // 최신순
                .doesNotContain(inProgress, otherCompleted);
        assertThat(response.items())
                .allSatisfy(item -> assertThat(item.status()).isEqualTo(OrderStatus.COMPLETED));
        assertThat(response.items())
                .isSortedAccordingTo(Comparator.comparing(RiderDeliveryHistoryItemResponse::completedAt).reversed());
    }

    @Test
    @DisplayName("페이지 크기만큼 잘라 반환하고 전체 건수는 그대로 유지한다")
    void paginatesWhilePreservingTotalCount() {
        Long riderId = newRider();
        Long first = completedDeliveryFor(riderId);
        sleepForDistinctCompletedAt();
        Long second = completedDeliveryFor(riderId);
        sleepForDistinctCompletedAt();
        Long third = completedDeliveryFor(riderId);

        RiderDeliveryHistoryListResponse page0 = riderDeliveryHistoryService.getDeliveryHistories(riderId, 0, 2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.items())
                .extracting(RiderDeliveryHistoryItemResponse::deliveryId)
                .containsExactly(third, second);

        RiderDeliveryHistoryListResponse page1 = riderDeliveryHistoryService.getDeliveryHistories(riderId, 1, 2);
        assertThat(page1.totalElements()).isEqualTo(3);
        assertThat(page1.items())
                .extracting(RiderDeliveryHistoryItemResponse::deliveryId)
                .containsExactly(first);
    }

    @Test
    @DisplayName("완료 기록이 없는 라이더는 빈 목록과 전체 건수 0을 받는다")
    void returnsEmptyWhenNoHistory() {
        Long riderId = newRider();

        RiderDeliveryHistoryListResponse response = riderDeliveryHistoryService.getDeliveryHistories(riderId, 0, 10);

        assertThat(response.totalElements()).isZero();
        assertThat(response.items()).isEmpty();
    }

    private Long newRider() {
        return tx().execute(t -> {
            Member member = memberRepository.save(member(MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            profile.goOnline();
            riderProfileRepository.save(profile);
            return profile.getMemberId();
        });
    }

    private Long completedDeliveryFor(Long riderId) {
        return tx().execute(t -> {
            RiderProfile rider = riderProfileRepository.findById(riderId).orElseThrow();
            DeliveryOrder order = newAssignedOrder(rider);
            order.startMovingToPickup();
            order.pickUp();
            order.startDelivering();
            order.complete();
            rider.release();
            return deliveryOrderRepository.save(order).getId();
        });
    }

    private Long deliveringDeliveryFor(Long riderId) {
        return tx().execute(t -> {
            RiderProfile rider = riderProfileRepository.findById(riderId).orElseThrow();
            DeliveryOrder order = newAssignedOrder(rider);
            order.startMovingToPickup();
            order.pickUp();
            order.startDelivering();
            return deliveryOrderRepository.save(order).getId();
        });
    }

    /** 새 고객으로 주문을 만들어 배차 확정(배송 ASSIGNED + 라이더 BUSY)까지 진행한다. */
    private DeliveryOrder newAssignedOrder(RiderProfile rider) {
        Member customer = memberRepository.save(member(MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(
                customer, UUID.randomUUID().toString(), ItemType.DOCUMENT, 3200,
                Address.of("서울 강남구 테헤란로 152", "101호", "06236",
                        new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                Address.of("서울 송파구 올림픽로 300", "5층", "05551",
                        new BigDecimal("37.5145"), new BigDecimal("127.1059")),
                Contact.of("김보내", "01011112222"),
                Contact.of("박받는", "01055556666"));
        order.assign(rider);
        rider.assign();
        return order;
    }

    private Member member(MemberRole role) {
        int n = sequence.incrementAndGet();
        String prefix = role == MemberRole.CUSTOMER ? "hist_c_" : "hist_r_";
        String phone = "010%s%06d".formatted(role == MemberRole.CUSTOMER ? "10" : "20", n);
        return Member.create(prefix + n, "pw", role == MemberRole.CUSTOMER ? "김고객" : "홍라이더", phone, role);
    }

    /** completed_at 이 DATETIME(3) 이라 같은 밀리초 완료를 피하려 잠깐 쉰다(FarePolicyTest 와 같은 이유). */
    private void sleepForDistinctCompletedAt() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
