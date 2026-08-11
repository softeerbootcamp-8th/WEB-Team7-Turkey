package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStatusHistoryTest {

    private static final String REQUEST_KEY = "11111111-1111-1111-1111-111111111111";

    private DeliveryOrder order() {
        return new DeliveryOrder();
    }

    private Member customer() {
        return Member.create("cust01", "hash", "홍길동", "01011112222", MemberRole.CUSTOMER);
    }

    @Test
    @DisplayName("회원 주체 전이는 actor와 상태정보를 보관한다")
    void shouldStoreActorAndStatusForMemberTransition() {
        DeliveryOrder order = order();
        Member actor = customer();

        OrderStatusHistory history = OrderStatusHistory.create(order,
                OrderStatus.WAITING, OrderStatus.CANCELED, "CANCEL",
                ActorType.CUSTOMER, actor, "고객 변심", REQUEST_KEY);

        assertThat(history.getOrder()).isSameAs(order);
        assertThat(history.getActor()).isSameAs(actor);
        assertThat(history.getActorType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(history.getPreviousStatus()).isEqualTo(OrderStatus.WAITING);
        assertThat(history.getNewStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(history.getAction()).isEqualTo("CANCEL");
        assertThat(history.getReason()).isEqualTo("고객 변심");
        assertThat(history.getRequestKey()).isEqualTo(REQUEST_KEY);
    }

    @Test
    @DisplayName("SYSTEM 전이는 actor가 없어야 한다")
    void shouldRequireNoActorForSystemTransition() {
        OrderStatusHistory history = OrderStatusHistory.create(order(),
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, "COMPLETE",
                ActorType.SYSTEM, null, null, REQUEST_KEY);

        assertThat(history.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(history.getActor()).isNull();
    }

    @Test
    @DisplayName("최초 기록은 previousStatus가 null일수 있다")
    void shouldAllowNullPreviousStatusForInitialRecord() {
        OrderStatusHistory history = OrderStatusHistory.create(order(),
                null, OrderStatus.WAITING, "REQUEST",
                ActorType.CUSTOMER, customer(), null, REQUEST_KEY);

        assertThat(history.getPreviousStatus()).isNull();
        assertThat(history.getNewStatus()).isEqualTo(OrderStatus.WAITING);
    }

    @Test
    @DisplayName("order는 null일수 없다")
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> OrderStatusHistory.create(null,
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("newStatus는 null일수 없다")
    void shouldRejectNullNewStatus() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, null, "ASSIGN",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("action은 공백일수 없다")
    void shouldRejectBlankAction() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "   ",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("action은 40자를 초과할수 없다")
    void shouldRejectActionLongerThanFortyCharacters() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "A".repeat(41),
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("actorType은 null일수 없다")
    void shouldRejectNullActorType() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                null, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("requestKey는 공백일수 없다")
    void shouldRejectBlankRequestKey() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, customer(), null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SYSTEM 전이는 actor를 가질수 없다")
    void shouldRejectActorForSystemTransition() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, "COMPLETE",
                ActorType.SYSTEM, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CUSTOMER 또는 RIDER 전이는 actor가 필요하다")
    void shouldRequireActorForCustomerOrRiderTransition() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, null, null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 상태로의 전이는 기록할수 없다")
    void shouldRejectTransitionToSameStatus() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.WAITING, "NOOP",
                ActorType.SYSTEM, null, null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
