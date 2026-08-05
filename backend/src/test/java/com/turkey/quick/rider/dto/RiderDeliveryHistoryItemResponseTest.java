package com.turkey.quick.rider.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운행 기록 목록 항목 매핑 단위 테스트. DB 없이 순수 객체로 {@code from} 팩토리만 검증한다.
 * 가장 흔한 매핑 실수(픽업/도착 주소 뒤바뀜, 상태 누락)를 여기서 막는다.
 */
class RiderDeliveryHistoryItemResponseTest {

    @Test
    @DisplayName("완료 배송을 목록 항목으로 옮기면 상태·물품·주소·거리·완료시각이 그대로 담기고 픽업/도착이 뒤바뀌지 않는다")
    void mapsCompletedOrderToItem() {
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

        RiderDeliveryHistoryItemResponse item = RiderDeliveryHistoryItemResponse.from(order);

        assertThat(item.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(item.itemType()).isEqualTo(ItemType.DOCUMENT);
        assertThat(item.pickupRoadAddress()).isEqualTo("서울 강남구 테헤란로 152");
        assertThat(item.destinationRoadAddress()).isEqualTo("서울 송파구 올림픽로 300");
        assertThat(item.straightDistanceMeters()).isEqualTo(3200);
        assertThat(item.completedAt()).isNotNull();
    }
}
