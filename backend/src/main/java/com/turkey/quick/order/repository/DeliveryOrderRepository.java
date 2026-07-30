package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배송요청 조회. <b>이 저장소에서 주문을 읽는 첫 경로다</b> — 엔터티와 DDL 은 있었지만
 * 조회·저장 코드가 한 줄도 없었고, 실시간 위치 구독(#77)이 첫 소비자다.
 *
 * <p>메서드를 미리 늘리지 않는다. 주문 목록·상세 조회는 그 API 를 구현하는 이슈에서 추가한다
 * ({@code CustomerDeliveryController} 의 나머지 메서드가 아직 {@code return null} 이다).
 * 호출자 없는 쿼리는 테스트만 통과시키고 아무것도 하지 않는다.
 */
public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {

    /**
     * 고객 본인의 배송요청을 실시간 구독 판정에 필요한 값만 뽑아 조회한다(#77 흐름 ②).
     *
     * <p><b>고객 조건을 쿼리에 넣는 이유</b>: 주문을 먼저 읽고 애플리케이션에서 소유자를 비교하면
     * "없음"과 "타인 것"이 갈리는데, 둘을 같은 404 로 응답하기로 정했다(존재 여부를 노출하지
     * 않는다). 조건을 쿼리에 두면 그 구분이 애초에 만들어지지 않아 실수로 새어 나갈 여지가 없다.
     *
     * <p><b>{@code o.assignedRider.memberId} 를 조인 없이 쓸 수 있는 이유</b>(실측으로 확인):
     * {@code RiderProfile} 은 {@code @MapsId} 로 {@code member} 와 PK 를 공유하므로 그 PK 가 곧
     * {@code delivery_order.assigned_rider_id} FK 컬럼값이다. 그래서 Hibernate 가 연관을
     * 초기화하지 않고 FK 컬럼만 읽는 SQL 을 만든다:
     * <pre>select do1_0.order_id, do1_0.assigned_rider_id, do1_0.status from delivery_order do1_0
     * where do1_0.order_id=? and do1_0.customer_id=?</pre>
     * {@code left join o.assignedRider} 를 명시하면 결과는 같지만 {@code rider_profile} 을
     * 실제로 조인하는 SQL 이 되어(확인함) 쓸 이유가 없다. <b>조인이 없으므로 라이더가 없는
     * {@code WAITING} 주문도 결과에 남는다</b> — 이건 이 조회의 계약이라 통합 테스트로 고정해 뒀다
     * ({@code rejectsWaitingDeliveryAsConflict}). 그 성질이 깨지면 배차 전 주문이 404 로 나가
     * 고객은 자기 주문이 사라진 것으로 본다.
     *
     * <p>엔터티를 로드하지 않고 투영으로 받으므로 {@link TrackableDelivery} 를 트랜잭션 밖에서
     * 만져도 안전하다. 그게 이 쿼리를 파생 메서드가 아니라 JPQL 로 쓴 이유다.
     *
     * @return 그 고객의 주문이 아니거나 존재하지 않으면 빈 결과. <b>상태는 걸러지지 않는다</b> —
     *         호출자가 {@code isTrackable()} 로 404 와 409 를 구분하기 위해서다
     */
    @Query("""
            select new com.turkey.quick.order.dto.TrackableDelivery(
                       o.id, o.assignedRider.memberId, o.status)
            from DeliveryOrder o
            where o.id = :deliveryId and o.customer.id = :customerId
            """)
    Optional<TrackableDelivery> findTrackableByIdAndCustomerId(@Param("deliveryId") Long deliveryId,
                                                              @Param("customerId") Long customerId);

    /**
     * 라이더가 지금 수행 중인 배송을 찾는다. 위치 이벤트를 어느 주문 채널로 발행할지 정하는 데
     * 쓴다(#78 흐름 ①).
     *
     * <p><b>결과가 최대 1건인 것은 DB 가 보장한다.</b> {@code delivery_order} 의 생성 컬럼
     * {@code active_rider_id} 에 걸린 {@code uk_delivery_active_rider} UNIQUE 가 "라이더당 진행 중
     * 배송 1건"을 강제하고, 그 컬럼의 CASE 식이 {@link OrderStatus#trackableStatuses()} 와 같은
     * 네 상태다. 그래서 "여러 건이면?" 분기가 필요 없다.
     *
     * <p><b>상태 조건이 안전장치다.</b> 배송이 완료·취소되면 결과가 비어 발행이 멈추고 고객의
     * 스트림은 조용해진다. 이 조건이 없으면 완료 후에도 그 라이더의 <i>다음</i> 배송 경로가
     * 이전 고객에게 계속 흘러간다 — 채널 키를 라이더가 아니라 주문으로 고른 이유가 이것이다.
     *
     * <p>이 조회는 <b>#78 전용이 아니다.</b> 위치 이력 저장(#102)도
     * {@code rider_location_history.order_id} 가 NOT NULL(V15)이라 같은 경로에서 주문을 풀어야
     * 한다. 그래서 {@code status} 를 함께 돌려준다 — 그쪽은 {@code DELIVERING} 만 저장하는 더
     * 좁은 정책이므로 받아서 스스로 걸러야 한다.
     */
    @Query("""
            select new com.turkey.quick.order.dto.TrackableDelivery(
                       o.id, o.assignedRider.memberId, o.status)
            from DeliveryOrder o
            where o.assignedRider.memberId = :riderId
              and o.status in :statuses
            """)
    Optional<TrackableDelivery> findInProgressByRiderId(@Param("riderId") Long riderId,
                                                       @Param("statuses") Set<OrderStatus> statuses);
    List<DeliveryOrder> findByStatus(OrderStatus status);
}
