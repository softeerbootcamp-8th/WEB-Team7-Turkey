package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.AddressResponse;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.order.service.DeliveryTimeoutService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.DeliveryRequestSort;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.SortDirection;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestCursor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestFilter;
import com.turkey.quick.rider.dto.RiderDeliveryRequestPageResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.geo.Point;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 콜(배차 대기 배송요청) 목록 조회(#55). 수락·상세·넘기기(#56/#57)는 이 서비스가 아니라
 * 각 이슈에서 추가한다.
 */
@RequiredArgsConstructor
@Service
public class RiderDeliveryRequestService {

    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DeliveryService deliveryService;

    /** #398: 배차 확정을 고객 추적 SSE로 실시간 전달하기 위해 주입한다. */
    private final TrackingPublisher trackingPublisher;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * AVAILABLE 라이더가 수락할 수 있는 배차 대기(WAITING) 배송요청 목록을 조회한다(#55/#367/#60).
     *
     * <p><b>동작 순서</b>
     * <ol>
     *   <li>라이더 상태·반경·페이지 크기·정렬 기준·정렬 방향·범위 필터(운임/배송거리) 값을
     *       검증한다. 위반하면 예외를 던진다(라이더 상태 위반은 403, 나머지는 400으로 변환).</li>
     *   <li>{@code latitude}·{@code longitude}가 둘 다 있으면(#367) bounding box 쿼리로
     *       {@code radiusMeters}를 감싸는 사각형 범위의 WAITING 주문만 가져온다. 하나라도 없으면
     *       WAITING 전부를 가져온다(#55 계약 확정 — 위치 없음은 에러가 아니다).</li>
     *   <li>배송요청마다 요약(summary)으로 변환하고, 반경(원)·운임 범위·배송거리 범위로 자바
     *       메모리에서 거른다(#60 계약 확정 — SQL로 내리지 않고 기존 반경 필터와 같은 구조 유지).</li>
     *   <li>정렬 기준·방향으로 전체를 정렬한다. DISTANCE인데 좌표가 없으면 REQUESTED_AT으로
     *       대체한다(#55 계약 확정). 정렬 방향을 생략하면 기준별 기본값(FARE=내림차순, 나머지=
     *       오름차순)을 쓴다.</li>
     *   <li>정렬된 전체 목록에서 {@code cursor}(이전 페이지 마지막 항목의 정렬값+id) 다음
     *       위치부터 {@code size}개(+1개 더)를 잘라, 실제로 {@code size}개를 넘겼으면
     *       {@code hasNext=true}로 표시하고 {@code size}개만 남긴다(#60 계약 확정 — keyset을
     *       SQL이 아니라 이 정렬·필터가 끝난 최종 목록 위에서 자바로 수행한다. 매 요청마다
     *       최신 목록을 다시 걸러 정렬하므로, 그 사이 목록이 바뀌어도(다른 라이더 수락 등)
     *       건너뜀·중복이 생기지 않는다).</li>
     * </ol>
     *
     * @param rider 세션 인증을 통과해 이미 식별된 현재 라이더
     * @param latitude 라이더 현재 위도. {@code longitude}와 둘 다 있어야 위치 필터가 적용된다.
     * @param longitude 라이더 현재 경도. {@code latitude}와 둘 다 있어야 위치 필터가 적용된다.
     * @param radiusMeters 검색 반경(m). 좌표가 있을 때만 실제로 적용된다.
     * @param sortParam {@code "DISTANCE"} · {@code "FARE"} · {@code "REQUESTED_AT"} 중 하나.
     * @param sortDirectionParam {@code "ASC"} · {@code "DESC"} 또는 {@code null}(기준별 기본값).
     * @param filter 운임·배송거리 범위 필터(#60). 전부 선택값.
     * @param size 페이지 크기(1~{@value #MAX_PAGE_SIZE}).
     * @param cursor 이전 페이지 마지막 항목의 커서(#60). 첫 페이지면 전부 {@code null}.
     * @return 이 페이지의 목록과 다음 페이지 존재 여부.
     */
    @Transactional(readOnly = true)
    public RiderDeliveryRequestPageResponse getDeliveryRequests(
            AuthenticatedRider rider, BigDecimal latitude, BigDecimal longitude, int radiusMeters,
            String sortParam, String sortDirectionParam,
            RiderDeliveryRequestFilter filter, int size, RiderDeliveryRequestCursor cursor) {

        requireAvailable(rider);
        requirePositiveRadius(radiusMeters);
        requireValidSize(size);
        requireValidRange(filter);
        DeliveryRequestSort sort = DeliveryRequestSort.from(sortParam);
        SortDirection requestedDirection = SortDirection.from(sortDirectionParam);

        boolean hasPosition = latitude != null && longitude != null;

        List<DeliveryOrder> waitingOrders = hasPosition
                ? findWithinBoundingBox(latitude, longitude, radiusMeters)
                : deliveryOrderRepository.findByStatus(OrderStatus.WAITING);
        if (waitingOrders.isEmpty()) {
            return new RiderDeliveryRequestPageResponse(List.of(), false);
        }

        Map<Long, OrderFareSnapshot> estimateByOrderId = loadEstimateSnapshots(waitingOrders);
        Optional<Point> riderPosition = hasPosition
                ? Optional.of(new Point(longitude.doubleValue(), latitude.doubleValue()))
                : Optional.empty();

        List<RiderDeliveryRequestSummaryResponse> summaries = waitingOrders.stream()
                .map(order -> toSummary(order, estimateSnapshotOf(order, estimateByOrderId), riderPosition))
                .filter(summary -> withinRadius(summary, riderPosition, radiusMeters))
                .filter(summary -> withinFareRange(summary, filter))
                .filter(summary -> withinDistanceRange(summary, filter))
                .collect(Collectors.toCollection(ArrayList::new));

        DeliveryRequestSort effectiveSort = (sort == DeliveryRequestSort.DISTANCE && riderPosition.isEmpty())
                ? DeliveryRequestSort.REQUESTED_AT
                : sort;
        SortDirection effectiveDirection = requestedDirection != null
                ? requestedDirection
                : defaultDirectionFor(effectiveSort);
        summaries.sort(comparatorFor(effectiveSort, effectiveDirection));

        requireCursorMatchesSort(cursor, effectiveSort);
        List<RiderDeliveryRequestSummaryResponse> afterCursor = summaries.stream()
                .filter(summary -> isAfterCursor(summary, effectiveSort, effectiveDirection, cursor))
                .toList();

        boolean hasNext = afterCursor.size() > size;
        List<RiderDeliveryRequestSummaryResponse> page = hasNext ? afterCursor.subList(0, size) : afterCursor;
        return new RiderDeliveryRequestPageResponse(List.copyOf(page), hasNext);
    }

    private List<DeliveryOrder> findWithinBoundingBox(BigDecimal latitude, BigDecimal longitude, int radiusMeters) {
        DeliveryService.BoundingBox box = deliveryService.boundingBox(latitude, longitude, radiusMeters);
        return deliveryOrderRepository.findWaitingOrdersWithinBoundingBox(
                box.latMin(), box.latMax(), box.lngMin(), box.lngMax());
    }

    /**
     * 배차 대기(WAITING) 배송요청 하나의 상세정보를 조회한다(#57).
     *
     * <p><b>동작 순서</b>
     * <ol>
     *   <li>라이더가 AVAILABLE 상태인지 검사한다(위반 시 403).</li>
     *   <li>{@code deliveryId}로 주문을 조회한다. 존재하지 않거나 상태가 WAITING이 아니면
     *       (이미 배차됐거나 취소됨) 404로 취급한다 — 배차 가능 여부만 라이더에게 의미가 있으므로
     *       "없다"와 "더 이상 대상이 아니다"를 구분해 알려주지 않는다.</li>
     *   <li>그 주문의 예상 운임 스냅샷(ESTIMATE)을 조회한다. 없으면 데이터 정합성 오류
     *       ({@link IllegalStateException}) — 목록 조회와 같은 불변식이다.</li>
     *   <li>상세 응답으로 변환한다. 픽업지·도착지는 도로명 주소만 포함하고 상세 주소(동·호수)는
     *       비운다 — 배차 확정 전에는 고객 개인정보를 노출하지 않는다는 계약(#55/#57 공통 정책).
     *       예상 소요시간은 저장된 직선거리를 {@link DeliveryService#estimateMinutes(int)}로
     *       환산해 계산한다(별도로 저장하지 않는다).</li>
     * </ol>
     *
     * @param rider 세션 인증을 통과해 이미 식별된 현재 라이더
     * @param deliveryId 조회할 배송요청 식별자
     * @return 상세정보. 주문이 없거나 WAITING이 아니면 {@link BusinessException}(404).
     */
    @Transactional(readOnly = true)
    public RiderDeliveryRequestDetailResponse getDeliveryRequest(AuthenticatedRider rider, Long deliveryId) {
        requireAvailable(rider);

        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .filter(candidate -> candidate.getStatus() == OrderStatus.WAITING)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "배차 가능한 배송요청이 아닙니다. deliveryId=" + deliveryId));

        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "배송요청에 예상 운임 스냅샷이 없습니다. orderId=" + order.getId()));

        int estimatedMinutes = deliveryService.estimateMinutes(order.getStraightDistanceMeters());

        return new RiderDeliveryRequestDetailResponse(
                order.getId(),
                order.getItemType(),
                toAddressResponseWithoutDetail(order.getPickup()),
                toAddressResponseWithoutDetail(order.getDestination()),
                order.getStraightDistanceMeters(),
                estimatedMinutes,
                FareBreakdownResponse.from(estimate),
                estimate.getTotalFare(),
                order.getRequestedAt());
    }

    /**
     * 배차 대기(WAITING) 배송요청을 라이더가 수락해 확정한다(#56, ADR-006).
     *
     * <p><b>동작 순서</b>
     * <ol>
     *   <li>라이더가 AVAILABLE 상태인지 검사한다(위반 시 403) — 세션 정보 기준 1차 검사일 뿐,
     *       최종 판정은 아래 조건부 UPDATE가 한다(세션과 DB 사이에 시차가 있을 수 있어서다).</li>
     *   <li><b>만료 정리(#42)는 이 트랜잭션 밖, 컨트롤러에서 먼저 수행된다.</b>
     *       {@code RiderDeliveryRequestController}가 이 메서드를 부르기 전에
     *       {@link DeliveryTimeoutService#cancelIfExpired}를 호출해, 배차 대기 타임아웃을 넘긴 주문을
     *       취소·환급까지 끝낸다. 그러면 아래 조건부 UPDATE가 자연히 0행이 되어 "이미 취소됨" 사유로
     *       409가 나간다 — 스캐너의 최대 폴링 간격만큼 라이더가 만료된 주문을 여전히 수락할 수 있는
     *       창을 없앤다. <b>왜 여기(이 트랜잭션 안)서 부르지 않는가</b>: {@code cancelIfExpired}는
     *       {@code REQUIRES_NEW}라, 이 {@code @Transactional}이 이미 확보한 커넥션을 정지시킨 채 두 번째
     *       커넥션을 요구한다. 그러면 accept 1건이 커넥션 2개를 동시에 점유해, 동시 요청이 풀 크기의
     *       절반을 넘으면 아무도 두 번째 커넥션을 못 얻어 풀이 교착됐다(#446, 부하 리허설에서 실측).
     *       컨트롤러에서 먼저 부르면 바깥 트랜잭션이 없어 만료 정리와 이 트랜잭션이 커넥션을 순차로만
     *       쓴다.</li>
     *   <li><b>주문 조건부 UPDATE</b>: {@code UPDATE delivery_order SET status=ASSIGNED, ... WHERE
     *       order_id=:deliveryId AND status='WAITING'}. 영향 행이 0이면 이 요청이 배차 경쟁에서
     *       졌거나(다른 라이더가 먼저 확정) 주문이 취소됐거나 존재하지 않는다는 뜻이다 — 주문을
     *       다시 조회해 정확한 사유로 예외를 던진다(존재하지 않으면 404, 취소/이미 배차면 409).
     *       같은 라이더가 서로 다른 두 주문을 동시에 수락하면 이 UPDATE 자체는 성공할 수 있지만
     *       {@code uk_delivery_active_rider} UNIQUE 위반으로 커밋 시점에 막히므로, 그 예외도
     *       여기서 409로 바꿔 던진다.</li>
     *   <li><b>라이더 조건부 UPDATE</b>: {@code UPDATE rider_profile SET operating_status=BUSY ...
     *       WHERE member_id=:riderId AND operating_status='AVAILABLE'}. 반드시 주문 UPDATE
     *       *다음에* 실행한다 — 두 UPDATE의 잠금 순서를 항상 "주문 → 라이더"로 고정해 데드락을
     *       피하는 게 ADR-006이 정한 팀 컨벤션이다. 영향 행이 0이면 이 라이더가 이미 다른 배송을
     *       수행 중이라는 뜻이며(예: 같은 라이더가 다른 주문을 먼저 수락함), 409로 실패하면서
     *       메서드 전체가 예외를 던지므로 방금 성공했던 주문 UPDATE도 트랜잭션과 함께 롤백된다
     *       (부분 성공 없음 — ADR-006 핵심 요구사항).</li>
     *   <li>두 UPDATE가 모두 성공하면 주문을 다시 조회해 최신 상태(ASSIGNED, 배차 시각)로 응답을
     *       구성한다.</li>
     * </ol>
     *
     * @param rider 세션 인증을 통과해 이미 식별된 현재 라이더
     * @param deliveryId 수락할 배송요청 식별자
     * @return 배차 확정 결과(주문 ASSIGNED, 라이더 BUSY, 배차 시각)
     */
    @Transactional
    public RiderDeliveryRequestAcceptResponse acceptDeliveryRequest(AuthenticatedRider rider, Long deliveryId) {
        requireAvailable(rider);
        // 만료 정리(#42)는 컨트롤러가 이 트랜잭션 밖에서 먼저 호출한다(위 동작 순서 참고, #446).

        LocalDateTime assignedAt = LocalDateTime.now(ZoneOffset.UTC);
        int orderUpdated;
        try {
            orderUpdated = deliveryOrderRepository.assignIfWaiting(deliveryId, rider.memberId(), assignedAt);
        } catch (DataIntegrityViolationException e) {
            // 같은 라이더가 다른 주문과 동시에 경쟁해 uk_delivery_active_rider 를 위반한 경우다
            // (ADR-006 "한 라이더 두 주문" 테이블 차원 백스톱).
            throw new BusinessException(HttpStatus.CONFLICT,
                    "라이더가 이미 다른 배송을 수행 중이라 배차를 확정할 수 없습니다.");
        }
        if (orderUpdated == 0) {
            throw notWaitingException(deliveryId);
        }

        int riderUpdated = riderProfileRepository.markBusyIfAvailable(rider.memberId());
        if (riderUpdated == 0) {
            // 주문 UPDATE 는 이미 성공했지만, 여기서 예외를 던져 트랜잭션 전체를 롤백시킨다
            // (ADR-006: 둘 다 1행일 때만 커밋, 부분 성공 금지).
            throw new BusinessException(HttpStatus.CONFLICT,
                    "라이더가 이미 다른 배송을 수행 중이라 배차를 확정할 수 없습니다.");
        }

        // 예전에는 배차 확정 직후 라이더를 GEO 배차 후보({@code riders:geo})에서 뺐지만, 라이더-측
        // GEO 사용처를 전부 제거하면서(#342, 디스커션 #338) 그 정리도 사라졌다 — 라이더를 GEO 에
        // 넣는 곳 자체가 없으므로 뺄 것도 없다. 배차 후보 자격은 라이더 operating_status(BUSY 로
        // 방금 전이됨)로만 판정된다.
        DeliveryOrder assigned = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 배차 확정한 주문을 다시 조회할 수 없습니다. orderId=" + deliveryId));

        trackingPublisher.publishStatus(deliveryId, assigned.getStatus(), assignedAt.toInstant(ZoneOffset.UTC));

        return new RiderDeliveryRequestAcceptResponse(
                assigned.getId(), assigned.getStatus(), OperatingStatus.BUSY, assigned.getAssignedAt());
    }

    /**
     * 주문 조건부 UPDATE가 0행이었을 때, 재조회로 정확한 실패 사유를 구분한다(ADR-006: "조건부
     * UPDATE는 0행만 돌려주고 이유는 알려주지 않으니, 실패 시 현재 상태를 재조회해 사유를
     * 구분한다"). 존재하지 않으면 404, 취소됐거나 이미 배차됐으면 409다.
     */
    private BusinessException notWaitingException(Long deliveryId) {
        return deliveryOrderRepository.findById(deliveryId)
                .<BusinessException>map(order -> switch (order.getStatus()) {
                    case CANCELED -> new BusinessException(HttpStatus.CONFLICT,
                            "배송요청이 취소되어 배차를 확정할 수 없습니다.");
                    // OrderStatus.canTransitionTo 상 WAITING으로 되돌아가는 전이가 없어 이 분기는
                    // 정상 흐름에서는 도달하지 않는다. switch 완전성 때문에 값을 채워야 해서,
                    // "이미 배차됨"처럼 잘못된 사유를 주지 않도록 재시도 안내로 방어만 해 둔다.
                    case WAITING -> new BusinessException(HttpStatus.CONFLICT,
                            "배차 확정에 실패했습니다. 다시 시도해 주세요.");
                    default -> new BusinessException(HttpStatus.CONFLICT,
                            "이미 다른 라이더가 배차를 확정했습니다.");
                })
                .orElseGet(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 배송요청입니다. deliveryId=" + deliveryId));
    }

    /** 배차 전에는 상세 주소(동·호수)를 노출하지 않는다 — 목록 응답과 같은 정책. */
    private AddressResponse toAddressResponseWithoutDetail(Address address) {
        return new AddressResponse(address.getRoadAddress(), null, address.getPostalCode(),
                address.getLatitude(), address.getLongitude());
    }

    private void requireAvailable(AuthenticatedRider rider) {
        if (rider.operatingStatus() != OperatingStatus.AVAILABLE) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "라이더 운행 상태가 AVAILABLE이 아닙니다.");
        }
    }

    private void requirePositiveRadius(int radiusMeters) {
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("검색 반경은 양수여야 합니다. radiusMeters=" + radiusMeters);
        }
    }

    /**
     * 페이지 크기는 양수여야 하고, {@value #MAX_PAGE_SIZE}을 넘을 수 없다(#60). 상한은 이슈에
     * 명시된 값은 아니고, 페이지네이션을 도입하는 이번 이슈 자체의 목적(무제한 응답 방지)에 맞춰
     * 방어적으로 추가했다 — radiusMeters 상한 미비(CLAUDE.md 기존 미결 항목)와는 별개 사안이다.
     */
    private void requireValidSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("페이지 크기는 양수여야 합니다. size=" + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "페이지 크기는 " + MAX_PAGE_SIZE + "을 넘을 수 없습니다. size=" + size);
        }
    }

    /** 운임·배송거리 범위 필터 값이 음수이거나 최소가 최대보다 크면 거부한다(#60). */
    private void requireValidRange(RiderDeliveryRequestFilter filter) {
        requireNonNegative(filter.fareMin(), "fareMin");
        requireNonNegative(filter.fareMax(), "fareMax");
        requireNonNegative(filter.distanceMin(), "distanceMin");
        requireNonNegative(filter.distanceMax(), "distanceMax");
        if (filter.fareMin() != null && filter.fareMax() != null && filter.fareMin() > filter.fareMax()) {
            throw new IllegalArgumentException("fareMin은 fareMax보다 클 수 없습니다.");
        }
        if (filter.distanceMin() != null && filter.distanceMax() != null
                && filter.distanceMin() > filter.distanceMax()) {
            throw new IllegalArgumentException("distanceMin은 distanceMax보다 클 수 없습니다.");
        }
    }

    private void requireNonNegative(Number value, String name) {
        if (value != null && value.doubleValue() < 0) {
            throw new IllegalArgumentException(name + "은 음수일 수 없습니다. " + name + "=" + value);
        }
    }

    /**
     * 커서의 정렬값 필드가 실제 적용될 정렬 기준({@code effectiveSort})과 안 맞으면 거부한다(#60).
     * DISTANCE 요청인데 좌표가 없어 REQUESTED_AT으로 대체된 경우(#55 계약), 클라이언트가 이전
     * 페이지에서 받은 {@code afterDistanceMeters}만 그대로 보내고 {@code afterRequestedAt}을
     * 안 채웠다면 여기서 걸린다 — 방치하면 아래 {@link #isAfterCursor}에서 NPE가 난다.
     */
    private void requireCursorMatchesSort(RiderDeliveryRequestCursor cursor, DeliveryRequestSort effectiveSort) {
        if (cursor == null || cursor.afterId() == null) {
            return;
        }
        boolean matches = switch (effectiveSort) {
            case DISTANCE -> cursor.afterDistanceMeters() != null;
            case FARE -> cursor.afterFare() != null;
            case REQUESTED_AT -> cursor.afterRequestedAt() != null;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "커서 값이 현재 정렬 기준(" + effectiveSort + ")과 맞지 않습니다.");
        }
    }

    /** 주문마다 스냅샷을 따로 조회하면 N+1(주문 조회 1번 + 주문당 조회 N번)이 되므로,
     * 전체 orderId를 한 번의 IN 절로 조회해 Map으로 올려두고 이후엔 DB 없이 꺼내 쓴다. */
    private Map<Long, OrderFareSnapshot> loadEstimateSnapshots(List<DeliveryOrder> orders) {
        List<Long> orderIds = orders.stream().map(DeliveryOrder::getId).toList();
        return orderFareSnapshotRepository.findByOrder_IdInAndFareType(orderIds, FareType.ESTIMATE).stream()
                .collect(Collectors.toMap(snapshot -> snapshot.getOrder().getId(), Function.identity()));
    }

    /** 주문 생성 시 ESTIMATE 스냅샷이 항상 함께 만들어져야 하므로(도메인 불변식), 없으면 데이터 정합성 오류다. */
    private OrderFareSnapshot estimateSnapshotOf(DeliveryOrder order, Map<Long, OrderFareSnapshot> byOrderId) {
        OrderFareSnapshot snapshot = byOrderId.get(order.getId());
        if (snapshot == null) {
            throw new IllegalStateException("배송요청에 예상 운임 스냅샷이 없습니다. orderId=" + order.getId());
        }
        return snapshot;
    }

    private RiderDeliveryRequestSummaryResponse toSummary(DeliveryOrder order, OrderFareSnapshot estimate,
                                                          Optional<Point> riderPosition) {
        Integer distanceToPickupMeters = riderPosition
                .map(point -> toMeters(deliveryService.distance(
                        BigDecimal.valueOf(point.getY()), BigDecimal.valueOf(point.getX()),
                        order.getPickup().getLatitude(), order.getPickup().getLongitude())))
                .orElse(null);

        return new RiderDeliveryRequestSummaryResponse(
                order.getId(),
                order.getItemType(),
                order.getPickup().getRoadAddress(),
                order.getDestination().getRoadAddress(),
                order.getStraightDistanceMeters(),
                distanceToPickupMeters,
                estimate.getTotalFare(),
                order.getRequestedAt());
    }

    /** 라이더 위치를 모르면 거리로 거를 수 없으므로 반경 필터를 건너뛰고 전체를 반환한다(#55 계약 확정). */
    private boolean withinRadius(RiderDeliveryRequestSummaryResponse summary, Optional<Point> riderPosition,
                                 int radiusMeters) {
        if (riderPosition.isEmpty()) {
            return true;
        }
        return summary.distanceToPickupMeters() <= radiusMeters;
    }

    /** fareMin/fareMax 둘 다 선택값이다 — null이면 그 방향 제한이 없다(#60). */
    private boolean withinFareRange(RiderDeliveryRequestSummaryResponse summary, RiderDeliveryRequestFilter filter) {
        if (filter.fareMin() != null && summary.expectedSettlementAmount() < filter.fareMin()) {
            return false;
        }
        return filter.fareMax() == null || summary.expectedSettlementAmount() <= filter.fareMax();
    }

    /** distanceMin/distanceMax는 배송거리(픽업→도착지)를 본다 — 라이더→픽업지 반경과는 다른 값이다(#60). */
    private boolean withinDistanceRange(RiderDeliveryRequestSummaryResponse summary,
                                        RiderDeliveryRequestFilter filter) {
        if (filter.distanceMin() != null && summary.straightDistanceMeters() < filter.distanceMin()) {
            return false;
        }
        return filter.distanceMax() == null || summary.straightDistanceMeters() <= filter.distanceMax();
    }

    /** 정렬 방향을 생략했을 때 정렬 기준별 기본값이다(#55/#367부터의 기존 동작을 그대로 기본값으로 유지). */
    private SortDirection defaultDirectionFor(DeliveryRequestSort sort) {
        return switch (sort) {
            case FARE -> SortDirection.DESC;
            case DISTANCE, REQUESTED_AT -> SortDirection.ASC;
        };
    }

    /**
     * 정렬 기준·방향에 맞는 비교자를 만든다. 어떤 기준이든 마지막에 {@code deliveryId} 오름차순을
     * tiebreaker로 덧붙인다(#60) — 동점(같은 운임, 같은 거리 등)이 있을 때 정렬 순서를 매 요청마다
     * 항상 같게 고정해야, 그 위에서 도는 커서 비교({@link #isAfterCursor})가 항목을 건너뛰거나
     * 중복시키지 않는다.
     */
    private Comparator<RiderDeliveryRequestSummaryResponse> comparatorFor(DeliveryRequestSort sort,
                                                                          SortDirection direction) {
        Comparator<RiderDeliveryRequestSummaryResponse> primary = switch (sort) {
            case DISTANCE -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::distanceToPickupMeters);
            case FARE -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::expectedSettlementAmount);
            case REQUESTED_AT -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::requestedAt);
        };
        if (direction == SortDirection.DESC) {
            primary = primary.reversed();
        }
        return primary.thenComparing(RiderDeliveryRequestSummaryResponse::deliveryId);
    }

    /**
     * 이 항목이 커서보다 뒤에 있는지 판정한다(#60, keyset 페이지네이션). 커서가 없으면(첫 페이지)
     * 전부 통과시킨다. 정렬 기준 값이 같으면 {@code deliveryId}로 갈라 판정한다 —
     * {@link #comparatorFor}가 쓰는 tiebreaker와 반드시 같은 규칙이어야 한다.
     */
    private boolean isAfterCursor(RiderDeliveryRequestSummaryResponse summary, DeliveryRequestSort sort,
                                  SortDirection direction, RiderDeliveryRequestCursor cursor) {
        if (cursor == null || cursor.afterId() == null) {
            return true;
        }

        int comparison = switch (sort) {
            case DISTANCE -> Integer.compare(summary.distanceToPickupMeters(), cursor.afterDistanceMeters());
            case FARE -> Long.compare(summary.expectedSettlementAmount(), cursor.afterFare());
            case REQUESTED_AT -> summary.requestedAt().compareTo(cursor.afterRequestedAt());
        };
        if (direction == SortDirection.DESC) {
            comparison = -comparison;
        }
        if (comparison != 0) {
            return comparison > 0;
        }
        return summary.deliveryId() > cursor.afterId();
    }

    private int toMeters(BigDecimal distanceKm) {
        return distanceKm.multiply(METERS_PER_KM).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
