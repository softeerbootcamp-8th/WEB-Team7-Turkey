package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추적 가능 상태 판정(#77). 7개 상태를 하나도 빠뜨리지 않고 덮는다.
 *
 * <p>기존 {@code OrderStatusTest} 에 덧붙이지 않고 파일을 분리한 이유: 그 파일은 전이 규칙
 * ({@code canTransitionTo})을 다루고, 이 판정은 전이와 무관한 별개 축이다.
 */
@DisplayName("OrderStatus 추적 가능 판정")
class OrderStatusTrackableTest {

    /**
     * DDL(V10)의 생성 컬럼 {@code active_rider_id} 의 CASE 식에 적힌 네 상태.
     * 리터럴로 다시 적어 둔 이유는 {@code isTrackable()} 을 그대로 호출해 비교하면 구현이
     * 무엇이든 통과하기 때문이다 — 여기서는 <b>기대값을 독립적으로</b> 적는다.
     */
    private static final Set<OrderStatus> EXPECTED_TRACKABLE = EnumSet.of(
            OrderStatus.ASSIGNED,
            OrderStatus.MOVING_TO_PICKUP,
            OrderStatus.PICKED_UP,
            OrderStatus.DELIVERING);

    @Test
    @DisplayName("라이더가 배정된 진행 중 상태만 추적할 수 있다")
    void allowsTrackingOnlyWhileAssignedAndInProgress() {
        Set<OrderStatus> actual = Arrays.stream(OrderStatus.values())
                .filter(OrderStatus::isTrackable)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(EXPECTED_TRACKABLE);
    }

    @Test
    @DisplayName("배차 전에는 추적할 수 없다")
    void rejectsTrackingBeforeAssignment() {
        // 라이더가 없어 보여 줄 위치가 없다. ck_delivery_assignment 가 이 상태에서 라이더 NULL 을
        // 강제하므로, 허용하면 riderId=null 로 Redis 위치를 조회하게 된다.
        assertThat(OrderStatus.WAITING.isTrackable()).isFalse();
    }

    @Test
    @DisplayName("끝난 배송은 추적할 수 없다")
    void rejectsTrackingAfterTermination() {
        // 허용하면 완료된 배송의 고객이 라이더의 '다음' 배송 경로를 계속 보게 된다.
        assertThat(OrderStatus.COMPLETED.isTrackable()).isFalse();
        assertThat(OrderStatus.CANCELED.isTrackable()).isFalse();
    }

    @Test
    @DisplayName("모든 상태가 판정된다")
    void decidesEveryStatus() {
        // switch 가 exhaustive 라 컴파일 단계에서 보장되지만, 상태가 늘었을 때 이 테스트의
        // 실패 메시지가 "판정을 정해야 한다"를 알려 주는 역할을 한다.
        assertThat(OrderStatus.values()).hasSize(7);
    }
}
