package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 배송 완료 트랜잭션에서 주문 한 행을 잠가 중복 완료를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DeliveryOrder o where o.id = :orderId")
    Optional<DeliveryOrder> findByIdForUpdate(@Param("orderId") Long orderId);

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
     * 고객 본인의 이용기록 목록(#43). 정렬은 호출자가 {@link Pageable} 에 담아 넘긴다 —
     * 최신순(requestedAt DESC) 고정이지만, 정렬 기준을 파생 메서드 이름에 박지 않고 Pageable 로
     * 넘기는 편이 이 메서드를 상태 필터 유무로 나눈 이유(아래)와 대칭을 이룬다.
     */
    Page<DeliveryOrder> findByCustomer_Id(Long customerId, Pageable pageable);

    /** status 필터가 있을 때만 쓴다. 파생 메서드를 둘로 나눈 이유는 status=null 을 "전체 상태"로
     * 해석하는 JPQL 조건(예: {@code and (:status is null or o.status = :status)})을 피하기 위해서다
     * — 옵티마이저가 매 호출 파라미터에 따라 인덱스를 다르게 타는 조건부 조건보다, 필터 유무로 쿼리
     * 자체를 나누는 쪽이 실행 계획이 안정적이다. */
    Page<DeliveryOrder> findByCustomer_IdAndStatus(Long customerId, OrderStatus status, Pageable pageable);

    /**
     * 라이더 본인의 운행 기록 목록(#70). 자신에게 배정되어 완료된 배송만 최신순으로 페이지 조회한다.
     * {@code assignedRider.memberId} 는 {@code existsByAssignedRider_MemberIdAndStatusIn} 과 같은
     * 파생 경로다(FK {@code assigned_rider_id} = RiderProfile PK). 정렬 기준은 호출자가 {@link Pageable}
     * 로 넘긴다(고객 목록과 대칭). 배차 이후 취소는 MVP 범위 밖이라 라이더 기록에는 COMPLETED 만 남는다.
     */
    Page<DeliveryOrder> findByAssignedRider_MemberIdAndStatus(Long riderId, OrderStatus status, Pageable pageable);

    /**
     * 배송요청 상세 조회(#46)용. {@link #findTrackableByIdAndCustomerId} 와 같은 이유로 고객 조건을
     * 쿼리에 둔다(없음/타인 것을 같은 404 로 응답). 상세는 상태를 가리지 않고(취소·완료 포함) 전체
     * 필드가 필요해 투영이 아니라 엔터티를 그대로 반환한다.
     */
    Optional<DeliveryOrder> findByIdAndCustomer_Id(Long id, Long customerId);

    /**
     * 추적 스냅샷(#79)이 응답을 조립하는 데 필요한 것을 <b>한 번에</b> 읽는다(#421에서 추가).
     *
     * <p>배정 라이더와 그 회원까지 {@code join fetch} 하는 이유는 <b>호출자가 트랜잭션 밖에서 응답을
     * 조립하기 때문이다.</b> #421 이 이 경로에 경로 탐색 호출(#431 기준 최대 3초)을 넣으면서
     * {@code DeliveryTrackingQueryService.getTracking} 의 트랜잭션을 걷어냈다 — 외부 HTTP 호출이
     * 끝날 때까지 DB 커넥션을 붙잡고 있으면 백업 폴링(1분 주기, #422) × 동시 추적 수만큼 커넥션이
     * 잠긴다. 그 대신 엔터티가 곧바로 준영속이 되므로, 지연 로딩된 연관을 만지는 순간
     * {@code LazyInitializationException} 이다(OSIV 가 꺼져 있다). 여기서 미리 채워 그 상황을 없앤다.
     * ({@code join fetch} 를 걷어내려면 트랜잭션을 되살려야 하고, 그러면 외부 호출이 커넥션을 잡는
     * 원래 문제로 돌아간다 — 둘은 한 묶음이다.)
     *
     * <p>고객 조건이 없는 것은 이 조회 앞에 {@code DeliveryTrackingAccessService} 의 소유권·상태
     * 판정이 반드시 오기 때문이다(그쪽이 404/409 를 담당한다).
     */
    @Query("""
            select o
            from DeliveryOrder o
            join fetch o.assignedRider r
            join fetch r.member
            where o.id = :deliveryId
            """)
    Optional<DeliveryOrder> findWithAssignedRiderById(@Param("deliveryId") Long deliveryId);

    /**
     * 라이더 본인의 운행 기록 상세 조회(#71)용. 목록({@link #findByAssignedRider_MemberIdAndStatus})과
     * 대칭으로 배정 조건을 쿼리에 두고(없음/타인 것을 같은 404 로 응답), 상태까지 조건에 넣어
     * COMPLETED 만 통과시킨다. 운행 기록은 완료 배송의 기록이므로, 진행 중 주문 id 를 넣어도 404 다 —
     * 이 조건이 곧 확정 운임(FINAL 스냅샷)·정산 내역이 반드시 존재한다는 불변식을 보장한다.
     */
    Optional<DeliveryOrder> findByIdAndAssignedRider_MemberIdAndStatus(Long id, Long riderId, OrderStatus status);

    /**
     * 라이더가 지금 수행 중인 배송을 상태까지 함께 조회한다. 화면 진입 시 한 번 부르는 경로용이다
     * ({@code RiderOperatingStatusQueryService}).
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
     * <p><b>위치 갱신 핫패스는 이 메서드를 쓰지 않는다.</b> 이 조회는
     * {@code idx_delivery_rider_completed (assigned_rider_id, completed_at DESC)} 를 타고 그 라이더의
     * <b>전체 주문 이력</b>을 훑은 뒤 상태로 걸러서, 운행 기간이 길어질수록 비용이 커진다. 5초 주기로
     * 불리는 쪽은 {@link #findInProgressIdByActiveRiderId} 를 쓴다.
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

    /**
     * 진행 배송 화면 복구에 필요한 주문 엔터티를 조회한다(#86).
     *
     * <p>DB 생성 컬럼의 UNIQUE 제약상 최대 1건이지만, #86 계약은 다건을 정합성 오류로 명시하므로
     * {@code Optional} 대신 목록으로 반환해 서비스가 결과 수를 직접 검증한다. 화면 진입 시 한 번만
     * 호출되는 조회이며, 응답 변환에 필요한 배정 라이더를 함께 로딩한다.
     */
    @Query("""
            select o
            from DeliveryOrder o
            join fetch o.assignedRider r
            where r.memberId = :riderId
              and o.status in :statuses
            """)
    List<DeliveryOrder> findAllInProgressForRider(@Param("riderId") Long riderId,
                                                 @Param("statuses") Set<OrderStatus> statuses);

    boolean existsByAssignedRider_MemberIdAndStatusIn(Long riderId, Set<OrderStatus> statuses);

    /**
     * 라이더가 수행 중인 배송의 식별자만 조회한다. <b>위치 갱신 핫패스 전용</b>(BUSY 라이더는 5초마다
     * 위치를 보낸다, #81).
     *
     * <p><b>생성 컬럼의 유니크 인덱스를 그대로 탄다.</b> {@code active_rider_id} 는 진행 중일 때만
     * {@code assigned_rider_id} 값을 갖고 그 외에는 NULL 인 생성 컬럼이고(V10),
     * {@code uk_delivery_active_rider} 가 걸려 있다. 그래서 이 조회는 <b>라이더당 최대 1행을 가리키는
     * 유니크 인덱스 단일 행 조회</b>이며 주문 이력이 아무리 쌓여도 비용이 늘지 않는다.
     * {@link #findInProgressByRiderId} 는 이력 전체를 훑은 뒤 상태로 걸러 그렇지 않다.
     *
     * <p><b>상태 조건을 SQL 에 쓰지 않는 이유</b>: 생성 컬럼의 CASE 식이 이미 그 조건이다. 대신
     * <b>추적 가능한 상태 집합이 DDL 과 {@link OrderStatus#trackableStatuses()} 두 곳에 존재하게
     * 된다</b> — 그 동치는 {@code DeliveryOrderActiveRiderIntegrationTest} 가 모든 상태를 돌며
     * 고정한다. 열거형에 상태를 추가하고 마이그레이션을 빠뜨리면 그 테스트가 깨진다.
     *
     * <p>JPQL 이 아니라 네이티브 쿼리인 이유: 생성 컬럼을 엔터티에 매핑하지 않아도 되고
     * ({@code ddl-auto: validate} 와 얽히지 않는다), 호출자가 필요한 것은 식별자 하나뿐이다.
     * 같은 리포지토리의 {@link #assignIfWaiting} 도 네이티브 쿼리다.
     */
    @Query(value = "SELECT order_id FROM delivery_order WHERE active_rider_id = :riderId",
           nativeQuery = true)
    Optional<Long> findInProgressIdByActiveRiderId(@Param("riderId") Long riderId);

    List<DeliveryOrder> findByStatus(OrderStatus status);

    /**
     * 라이더 콜 목록 위치 검색(#367)의 bounding box 쿼리. status·픽업 위경도가 사각형 범위 안에
     * 있는 WAITING 주문만 가져온다 — {@code idx_delivery_waiting_location(status,
     * pickup_latitude, pickup_longitude)}를 강제로 탄다(V10에 있었지만 쓰는 쿼리가 없었다).
     *
     * <p><b>파생 메서드가 아니라 {@code FORCE INDEX} 네이티브 쿼리인 이유</b>(PR #378 리뷰,
     * discussion #380 실측): WAITING이 테이블 전체의 100%에 가깝고 절대 건수가 일정 임계치
     * (실측 약 2만 건)를 넘으면, 옵티마이저가 이 인덱스 대신 {@code idx_delivery_status_requested}
     * 로 갈아타면서 응답 시간이 77배까지 뛰는 현상이 실측으로 확인됐다(비커버링 인덱스라 걸러진
     * 행마다 원본을 다시 읽어야 해서다). COMPLETED·CANCELED가 영구히 누적되는 이 테이블 특성상
     * WAITING이 100%에 가까워질 일은 실제로는 거의 없지만, 강제 지정 자체가 정상 범위에서
     * 성능 손해가 없음이 같이 확인돼(#380 4-2장) 예방 차원에서 항상 강제한다. JPQL은 DB 종속
     * 힌트를 표현할 방법이 없어 네이티브 SQL이 필요하다.
     *
     * <p>사각형은 실제 반경(원)보다 넓다(4/π ≈ 1.27배) — 모서리에 걸리는 후보는 이 쿼리로
     * 걸러지지 않는다. 호출자({@code RiderDeliveryRequestService})가 하버사인 거리로 다시
     * 걸러 원 밖을 제외한다.
     */
    @Query(value = "SELECT * FROM delivery_order FORCE INDEX (idx_delivery_waiting_location) "
            + "WHERE status = 'WAITING' "
            + "AND pickup_latitude BETWEEN :latMin AND :latMax "
            + "AND pickup_longitude BETWEEN :lngMin AND :lngMax",
            nativeQuery = true)
    List<DeliveryOrder> findWaitingOrdersWithinBoundingBox(
            @Param("latMin") BigDecimal latMin, @Param("latMax") BigDecimal latMax,
            @Param("lngMin") BigDecimal lngMin, @Param("lngMax") BigDecimal lngMax);

    /**
     * 배차 대기 시간 초과 자동 취소(#42)의 능동 스캐너용 조회. WAITING 이면서 {@code requestedAt}
     * 이 컷오프보다 이전인 주문을 전부 가져온다. {@code idx_delivery_status_requested
     * (status, requested_at)} 를 그대로 탄다.
     *
     * <p>결과를 스캐너가 순회하며 {@link #cancelIfWaiting} 을 한 건씩 호출한다 — 한 건이 실패해도
     * (예: 그 사이 라이더가 배차를 확정함) 나머지에 영향을 주지 않기 위해서다.
     */
    List<DeliveryOrder> findByStatusAndRequestedAtBefore(OrderStatus status, LocalDateTime cutoff);

    /**
     * 고객의 현재 진행 중 배송요청을 조회한다(지연 만료, #42). {@code active_customer_id} 생성 컬럼을
     * 그대로 읽는다 — "진행 중"의 정의(WAITING~DELIVERING)를 JPQL 에 다시 나열하지 않기 위해서다.
     * 그 컬럼의 UNIQUE 제약상 결과는 최대 1건이다.
     *
     * <p>{@link #findInProgressIdByActiveRiderId} 와 같은 이유로 네이티브 쿼리다 — 생성 컬럼은
     * 엔터티에 매핑돼 있지 않다.
     */
    @Query(value = "SELECT * FROM delivery_order WHERE active_customer_id = :customerId",
           nativeQuery = true)
    Optional<DeliveryOrder> findActiveByCustomerId(@Param("customerId") Long customerId);

    /**
     * 배차 대기 시간 초과 자동 취소(#42)의 조건부 UPDATE. WAITING 인 행만 CANCELED 로 바꾸고
     * 영향 행 수(0 또는 1)로 성공 여부를 판정한다 — {@link #assignIfWaiting} 과 같은 ADR-006 패턴이다.
     *
     * <p>0 이면 그 사이 배차가 확정됐거나(#56) 이미 취소된 것이다. 둘 다 정상 흐름이며 예외가 아니다
     * — 배차 확정과 자동 취소가 동시에 벌어져도 정확히 하나만 성공한다는 #42 의 성공 조건이 이 조건
     * 하나로 보장된다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE delivery_order SET status = 'CANCELED', canceled_at = :canceledAt, "
            + "cancel_reason = :reason WHERE order_id = :orderId AND status = 'WAITING'", nativeQuery = true)
    int cancelIfWaiting(@Param("orderId") Long orderId, @Param("canceledAt") LocalDateTime canceledAt,
                       @Param("reason") String reason);

    /**
     * 요청 멱등키로 기존 주문을 찾는다(#37 흐름 ③). {@code uk_delivery_customer_request}
     * (customer_id, request_key) 와 같은 조합이라 결과는 0 또는 1건이다.
     *
     * <p><b>이 조회만으로 중복 생성이 막히지는 않는다.</b> 조회와 INSERT 사이에 다른 요청이 끼어들 수
     * 있어서, 진짜 동시 재전송은 위 UNIQUE 제약이 잡는다. 이 메서드는 순차 재전송(따닥 클릭, 네트워크
     * 재시도)을 오류가 아니라 "기존 결과 반환"으로 흡수하기 위한 것이다.
     */
    Optional<DeliveryOrder> findByCustomer_IdAndRequestKey(Long customerId, String requestKey);

    /**
     * 배차 확정(#56)의 첫 번째 조건부 UPDATE(ADR-006 Compare-And-Set). WAITING인 행만
     * ASSIGNED로 바꾸고, 영향 행 수(0 또는 1)로 "이 요청이 배차 경쟁에서 이겼는가"를 판정한다.
     * 항상 {@link com.turkey.quick.rider.repository.RiderProfileRepository#markBusyIfAvailable}
     * 보다 먼저 호출한다(ADR-006이 고정한 잠금 순서: 주문 → 라이더, 데드락 회피).
     *
     * <p>이 WHERE 조건은 "한 주문에 여러 라이더"만 막는다 — 서로 다른 주문(행)끼리는 이 조건이
     * 서로를 전혀 모르기 때문에, 같은 라이더가 서로 다른 두 주문을 동시에 수락하면 이 UPDATE
     * 자체는 (각자 자기 행 기준으로) 둘 다 성공할 수 있다. 그 대신 {@code uk_delivery_active_rider}
     * UNIQUE 제약이 테이블 전체에서 "한 라이더는 진행 중 주문 1건" 을 강제하므로, 두 번째 UPDATE가
     * 커밋되는 시점에 충돌을 일으켜 {@link org.springframework.dao.DataIntegrityViolationException}
     * 으로 막힌다(ADR-006 "한 라이더 두 주문" 테이블 차원 백스톱). 호출부에서 이 예외를 잡아
     * 409로 변환해야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE delivery_order SET status = 'ASSIGNED', assigned_rider_id = :riderId, "
            + "assigned_at = :assignedAt WHERE order_id = :orderId AND status = 'WAITING'", nativeQuery = true)
    int assignIfWaiting(@Param("orderId") Long orderId, @Param("riderId") Long riderId,
                        @Param("assignedAt") LocalDateTime assignedAt);

    /**
     * 배정된 라이더의 픽업지 이동 시작(#58)을 조건부 UPDATE로 처리한다.
     * 주문·라이더·현재 상태를 WHERE 절에서 함께 검증하므로 같은 요청이 동시에 들어와도
     * 정확히 한 요청만 ASSIGNED → MOVING_TO_PICKUP 전이에 성공한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE delivery_order "
            + "SET status = 'MOVING_TO_PICKUP', moving_to_pickup_at = :startedAt, updated_at = :startedAt "
            + "WHERE order_id = :orderId AND assigned_rider_id = :riderId AND status = 'ASSIGNED'",
            nativeQuery = true)
    int startMovingToPickupIfAssigned(@Param("orderId") Long orderId,
                                      @Param("riderId") Long riderId,
                                      @Param("startedAt") LocalDateTime startedAt);

    /**
     * 배정된 라이더의 물품 픽업 완료(#59)를 조건부 UPDATE로 처리한다.
     * MOVING_TO_PICKUP 상태인 한 요청만 PICKED_UP으로 바꾸므로 중복·동시 요청은 하나만 성공한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE delivery_order "
            + "SET status = 'PICKED_UP', picked_up_at = :pickedUpAt, updated_at = :pickedUpAt "
            + "WHERE order_id = :orderId AND assigned_rider_id = :riderId AND status = 'MOVING_TO_PICKUP'",
            nativeQuery = true)
    int pickUpIfMovingToPickup(@Param("orderId") Long orderId,
                               @Param("riderId") Long riderId,
                               @Param("pickedUpAt") LocalDateTime pickedUpAt);

    /** PICKED_UP 상태인 배정 주문 한 건만 DELIVERING으로 전이하고 배송 시작 시각을 기록한다(#65). */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE delivery_order "
            + "SET status = 'DELIVERING', delivering_at = :deliveringAt, updated_at = :deliveringAt "
            + "WHERE order_id = :orderId AND assigned_rider_id = :riderId AND status = 'PICKED_UP'",
            nativeQuery = true)
    int startDeliveringIfPickedUp(@Param("orderId") Long orderId,
                                  @Param("riderId") Long riderId,
                                  @Param("deliveringAt") LocalDateTime deliveringAt);
}
