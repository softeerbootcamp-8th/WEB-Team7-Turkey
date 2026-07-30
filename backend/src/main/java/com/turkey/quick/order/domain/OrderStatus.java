package com.turkey.quick.order.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 배송 주문의 상태 흐름.
 *
 * WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED
 *
 * 취소는 배차 전(WAITING)에만 허용한다 — 배차 이후 취소는 MVP 범위 밖이며(라이더 배차 포기도 마찬가지),
 * DDL 의 ck_delivery_assignment 도 같은 규칙을 강제한다: status='CANCELED' 인 행은
 * assigned_rider_id/assigned_at 이 NULL 이어야 하므로 라이더가 배정된 주문은 CANCELED 로 갈 수 없다.
 *
 * 상태 변경은 요청 값으로 덮어쓰지 않고 "현재 상태 + 수행 행위"로 검증한다.
 * 이 enum 은 전이 가능 여부만 판정하고, 실제 전이와 시각 컬럼 기록은 DeliveryOrder 가 수행한다.
 */
public enum OrderStatus {

    /** 배차 대기: 주문 생성 직후, 라이더를 찾는 중 */
    WAITING,

    /** 배차 완료: 라이더가 콜을 수락해 배정됨(라이더는 이 시점에 BUSY 가 된다) */
    ASSIGNED,

    /** 픽업지로 이동 중 */
    MOVING_TO_PICKUP,

    /** 픽업 완료: 라이더가 물품을 수령함 */
    PICKED_UP,

    /** 배송 중: 도착지로 이동 중 */
    DELIVERING,

    /** 완료: 도착지 전달 완료(정산 내역이 생성된다) */
    COMPLETED,

    /** 취소: 배차 전 고객 취소만 존재한다 */
    CANCELED;

    private Set<OrderStatus> nextStates() {
        return switch (this) {
            case WAITING -> Set.of(ASSIGNED, CANCELED);
            case ASSIGNED -> Set.of(MOVING_TO_PICKUP);
            case MOVING_TO_PICKUP -> Set.of(PICKED_UP);
            case PICKED_UP -> Set.of(DELIVERING);
            case DELIVERING -> Set.of(COMPLETED);
            case COMPLETED, CANCELED -> Set.of();
        };
    }

    /** 현재 상태에서 target 상태로 전이 가능한지 검증한다. */
    public boolean canTransitionTo(OrderStatus target) {
        return nextStates().contains(target);
    }

    /**
     * 고객이 실시간 위치를 구독할 수 있는 상태인가(#77).
     *
     * <p>라이더가 배정돼 있고 아직 끝나지 않은 구간이다. 이 네 값은 DDL(V10)의 생성 컬럼
     * {@code active_rider_id} 의 CASE 식과 <b>정확히 같아야 한다</b> — 그 컬럼의 UNIQUE 가
     * "라이더당 진행 중 배송 1건"의 근거이고, 위치 이벤트를 발행할 때 라이더로 주문을 되찾는
     * 조회(#78)도 같은 집합을 쓴다. 두 집합이 갈리면 "구독은 되는데 발행되지 않는" 배송이 생긴다.
     *
     * <p>{@code WAITING} 이 빠지는 이유는 라이더가 없어 보여 줄 위치가 없다는 것이고
     * ({@code ck_delivery_assignment} 가 그 상태에서 라이더 NULL 을 강제한다),
     * {@code COMPLETED}·{@code CANCELED} 는 끝난 배송이다.
     *
     * <p><b>위치 이력 저장(#102)은 이 집합을 쓰지 않는다.</b> {@code rider_location_history} 는
     * {@code DELIVERING} 구간만 저장하는 정책으로 고정돼 있어(V15 주석 — 그래서 그 테이블에
     * 상태 컬럼이 없다) 여기보다 좁다. 둘이 같은 조회를 공유하더라도 판정은 각자 해야 한다 —
     * 통일하면 픽업 이동 구간의 좌표가 이력에 섞여 들어가고, 그 테이블은 "실제 운행 구간"이라는
     * 전제를 잃는다.
     *
     * <p>{@code nextStates} 처럼 switch 로 쓴 것은 의도적이다. 상태가 하나 늘면
     * <b>컴파일이 깨져</b> 판정을 강제로 정하게 된다 — 기본값으로 조용히 흘러가면 새 상태가
     * 추적 불가(마커가 안 뜬다)나 추적 가능(끝난 배송이 새어 나간다) 중 하나로 잘못 굳는다.
     */
    public boolean isTrackable() {
        return switch (this) {
            case ASSIGNED, MOVING_TO_PICKUP, PICKED_UP, DELIVERING -> true;
            case WAITING, COMPLETED, CANCELED -> false;
        };
    }

    /**
     * {@link #isTrackable()} 인 상태들. 쿼리의 {@code in} 절에 넣기 위한 것이다.
     *
     * <p><b>목록을 다시 적지 않고 위 switch 에서 파생시킨다.</b> 두 곳에 적으면 상태가 늘었을 때
     * 한쪽만 고쳐지고, 그 결과는 "구독은 되는데 위치가 발행되지 않는" 배송이다 — 증상이
     * "지도가 안 움직인다"라서 원인을 찾기 어렵다.
     */
    public static Set<OrderStatus> trackableStatuses() {
        return TRACKABLE;
    }

    /** enum 상수가 만들어진 뒤에 초기화되므로 {@code values()} 와 {@code isTrackable()} 을 쓸 수 있다. */
    private static final Set<OrderStatus> TRACKABLE = Arrays.stream(values())
            .filter(OrderStatus::isTrackable)
            .collect(Collectors.toUnmodifiableSet());
}
