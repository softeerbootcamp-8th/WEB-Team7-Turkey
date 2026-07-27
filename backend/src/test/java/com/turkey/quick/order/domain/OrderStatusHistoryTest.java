package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
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
    void 회원_주체_전이는_actor와_상태정보를_보관한다() {
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
    void SYSTEM_전이는_actor가_없어야_한다() {
        OrderStatusHistory history = OrderStatusHistory.create(order(),
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, "COMPLETE",
                ActorType.SYSTEM, null, null, REQUEST_KEY);

        assertThat(history.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(history.getActor()).isNull();
    }

    @Test
    void 최초_기록은_previousStatus가_null일수_있다() {
        OrderStatusHistory history = OrderStatusHistory.create(order(),
                null, OrderStatus.WAITING, "REQUEST",
                ActorType.CUSTOMER, customer(), null, REQUEST_KEY);

        assertThat(history.getPreviousStatus()).isNull();
        assertThat(history.getNewStatus()).isEqualTo(OrderStatus.WAITING);
    }

    @Test
    void order는_null일수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(null,
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newStatus는_null일수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, null, "ASSIGN",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void action은_공백일수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "   ",
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void action은_40자를_초과할수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "A".repeat(41),
                ActorType.RIDER, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorType은_null일수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                null, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestKey는_공백일수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, customer(), null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SYSTEM_전이는_actor를_가질수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, "COMPLETE",
                ActorType.SYSTEM, customer(), null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void CUSTOMER_또는_RIDER_전이는_actor가_필요하다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.ASSIGNED, "ASSIGN",
                ActorType.RIDER, null, null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_상태로의_전이는_기록할수_없다() {
        assertThatThrownBy(() -> OrderStatusHistory.create(order(),
                OrderStatus.WAITING, OrderStatus.WAITING, "NOOP",
                ActorType.SYSTEM, null, null, REQUEST_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
