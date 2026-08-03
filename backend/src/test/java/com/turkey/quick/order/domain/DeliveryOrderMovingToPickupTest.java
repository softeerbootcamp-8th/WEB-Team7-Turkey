package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.RiderProfile;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeliveryOrderMovingToPickupTest {

    @Test
    @DisplayName("배차된 주문은 픽업지 이동 중 상태로 전이하고 시작 시각을 기록한다")
    void shouldStartMovingToPickupFromAssigned() {
        DeliveryOrder order = assignedOrder();

        order.startMovingToPickup();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.MOVING_TO_PICKUP);
        assertThat(order.getMovingToPickupAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 픽업지로 이동 중인 주문의 중복 전이는 거부한다")
    void shouldRejectDuplicateStartMovingToPickup() {
        DeliveryOrder order = assignedOrder();
        order.startMovingToPickup();

        assertThatThrownBy(order::startMovingToPickup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOVING_TO_PICKUP → MOVING_TO_PICKUP");
    }

    private DeliveryOrder assignedOrder() {
        Member customer = Member.create("unit_customer_58", "hash", "고객", "01011112222", MemberRole.CUSTOMER);
        Member riderMember = Member.create("unit_rider_58", "hash", "라이더", "01033334444", MemberRole.RIDER);
        RiderProfile rider = RiderProfile.create(riderMember);
        DeliveryOrder order = DeliveryOrder.request(
                customer, "request-key-58", ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지", "상세", "12345", new BigDecimal("37.5000000"),
                        new BigDecimal("127.0000000")),
                Address.of("도착지", "상세", "54321", new BigDecimal("37.6000000"),
                        new BigDecimal("127.1000000")),
                Contact.of("보내는 사람", "01011112222"),
                Contact.of("받는 사람", "01033334444"));
        order.assign(rider);
        return order;
    }
}
