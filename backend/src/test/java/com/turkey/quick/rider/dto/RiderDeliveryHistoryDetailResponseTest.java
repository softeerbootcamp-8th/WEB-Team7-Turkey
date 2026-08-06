package com.turkey.quick.rider.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.payment.domain.RiderSettlement;
import com.turkey.quick.rider.domain.RiderProfile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운행 기록 상세 매핑 단위 테스트. DB 없이 순수 객체로 {@code from} 팩토리만 검증한다.
 * 목록과 달리 상세는 운임·정산·타임라인·완료 인증을 한 응답에 조립하므로, 그 조립에서 나기 쉬운
 * 실수(픽업/도착 뒤바뀜, 정산액 누락, proof 없음 처리)를 여기서 막는다.
 * 정산 시각은 JPA 생명주기(@PrePersist)에 달려 순수 객체에서는 null 이므로 여기서 검증하지 않는다
 * (실제 저장·조회는 통합 테스트가 덮는다).
 */
class RiderDeliveryHistoryDetailResponseTest {

    @Test
    @DisplayName("완료 배송을 상세로 옮기면 주소·거리·운임·정산액·타임라인·인증이 담기고 픽업/도착이 뒤바뀌지 않는다")
    void mapsCompletedOrderToDetail() {
        DeliveryOrder order = completedOrder();
        OrderFareSnapshot finalSnapshot = finalSnapshotFor(order);
        RiderSettlement settlement = RiderSettlement.settle(finalSnapshot, 6400L);
        RiderProfile rider = order.getAssignedRider();
        var proof = com.turkey.quick.order.domain.DeliveryProof.create(
                order, rider, ProofType.RECIPIENT_CONFIRMATION, "recipient-1024");

        RiderDeliveryHistoryDetailResponse detail = RiderDeliveryHistoryDetailResponse.from(
                order, FareBreakdownResponse.from(finalSnapshot), settlement, proof, "recipient-1024");

        assertThat(detail.itemType()).isEqualTo(ItemType.DOCUMENT);
        assertThat(detail.pickup().roadAddress()).isEqualTo("서울 강남구 테헤란로 152");
        assertThat(detail.destination().roadAddress()).isEqualTo("서울 송파구 올림픽로 300");
        assertThat(detail.straightDistanceMeters()).isEqualTo(3200);
        assertThat(detail.finalFare().totalFare()).isEqualTo(6400L);
        assertThat(detail.settlementAmount()).isEqualTo(6400L);
        // WAITING(requestedAt)은 @PrePersist 에서 채워져 순수 객체에는 없다 — 파생 순서·전이 누락만
        // 여기서 검증하고, WAITING 포함 전체 타임라인은 통합 테스트가 실제 저장 후 확인한다.
        assertThat(detail.steps()).extracting(step -> step.status())
                .containsExactly(OrderStatus.ASSIGNED, OrderStatus.MOVING_TO_PICKUP,
                        OrderStatus.PICKED_UP, OrderStatus.DELIVERING, OrderStatus.COMPLETED);
        assertThat(detail.proofType()).isEqualTo(ProofType.RECIPIENT_CONFIRMATION);
        assertThat(detail.proofValue()).isEqualTo("recipient-1024");
    }

    @Test
    @DisplayName("완료 인증 기록이 없으면 인증 방식·참조값이 모두 null 로 담긴다")
    void mapsNullProofWhenAbsent() {
        DeliveryOrder order = completedOrder();
        OrderFareSnapshot finalSnapshot = finalSnapshotFor(order);
        RiderSettlement settlement = RiderSettlement.settle(finalSnapshot, 6400L);

        RiderDeliveryHistoryDetailResponse detail = RiderDeliveryHistoryDetailResponse.from(
                order, FareBreakdownResponse.from(finalSnapshot), settlement, null, null);

        assertThat(detail.proofType()).isNull();
        assertThat(detail.proofValue()).isNull();
    }

    private DeliveryOrder completedOrder() {
        Member customer = Member.create("hist_c", "pw", "김고객", "01011112222", MemberRole.CUSTOMER);
        Member riderMember = Member.create("hist_r", "pw", "홍라이더", "01033334444", MemberRole.RIDER);
        RiderProfile rider = RiderProfile.create(riderMember);
        rider.goOnline();

        DeliveryOrder order = DeliveryOrder.request(
                customer, UUID.randomUUID().toString(), ItemType.DOCUMENT, 3200,
                Address.of("서울 강남구 테헤란로 152", "101호", "06236",
                        new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                Address.of("서울 송파구 올림픽로 300", "5층", "05551",
                        new BigDecimal("37.5145"), new BigDecimal("127.1059")),
                Contact.of("김보내", "01011112222"),
                Contact.of("박받는", "01055556666"));
        order.assign(rider);
        order.startMovingToPickup();
        order.pickUp();
        order.startDelivering();
        order.complete();
        return order;
    }

    private OrderFareSnapshot finalSnapshotFor(DeliveryOrder order) {
        FarePolicy policy = FarePolicy.create("v1", 3000L, 100, 130L, 30000,
                LocalDateTime.now().minusDays(1));
        return OrderFareSnapshot.create(order, policy, FareType.FINAL, policy.getPolicyVersion(),
                3200, 3000L, 2400L, 1000L);
    }
}
