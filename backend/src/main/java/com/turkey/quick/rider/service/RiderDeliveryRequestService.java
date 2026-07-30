package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.repository.RiderGeoRepository;
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
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.DeliveryRequestSort;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
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
    private final RiderGeoRepository riderGeoRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DeliveryService deliveryService;

    /**
     * AVAILABLE 라이더가 수락할 수 있는 배차 대기(WAITING) 배송요청 목록을 조회한다.
     *
     * <p><b>동작 순서</b>
     * <ol>
     *   <li>라이더가 AVAILABLE 상태인지, {@code radiusMeters}가 양수인지, {@code sortParam}이
     *       DISTANCE/FARE/REQUESTED_AT 중 하나인지 검사한다. 위반하면 예외를 던진다
     *       (라이더 상태 위반은 403, 나머지는 400으로 변환된다).</li>
     *   <li>WAITING 상태인 배송요청을 전부 조회한다. 없으면 빈 목록을 바로 반환한다.</li>
     *   <li>배송요청마다 "예상 운임 스냅샷"(주문 생성 시점에 계산·저장된 ESTIMATE 운임,
     *       {@link OrderFareSnapshot})을 한 번에 조회해 orderId로 매핑해 둔다 — 배송요청 개수만큼
     *       따로 조회하지 않기 위해서다(N+1 방지). 스냅샷이 없는 배송요청이 있으면 데이터 정합성
     *       오류로 보고 예외를 던진다(정상 생성된 주문은 스냅샷이 항상 함께 있어야 한다).</li>
     *   <li>라이더의 최신 위치를 Redis({@code riders:geo})에서 조회한다. 위치는 없을 수도 있다
     *       (라이더가 아직 위치를 전송한 적이 없거나, 위치 전송 기능 자체가 아직 없는 경우).</li>
     *   <li>배송요청마다 응답 한 줄(summary, {@link RiderDeliveryRequestSummaryResponse})로
     *       변환한다. 라이더 위치를 알면 라이더→픽업지 거리를 계산해 채우고, 모르면 거리 필드는
     *       {@code null}로 남긴다.</li>
     *   <li>라이더 위치를 알면 {@code radiusMeters} 반경 밖의 배송요청은 결과에서 뺀다. 위치를
     *       모르면 거리로 거를 수 없으므로 반경 필터 없이 전체를 반환한다(위치 미확보를 에러로
     *       취급하지 않는다 — #55 계약 확정).</li>
     *   <li>{@code sortParam}에 따라 정렬한다: DISTANCE(가까운 순) · FARE(예상 정산액 높은 순) ·
     *       REQUESTED_AT(오래 기다린 순). 단 DISTANCE를 요청했는데 라이더 위치를 몰라 거리를 계산할
     *       수 없으면 REQUESTED_AT으로 대체한다.</li>
     * </ol>
     *
     * @param rider 세션 인증을 통과해 이미 식별된 현재 라이더
     * @param radiusMeters 검색 반경(m). 라이더 위치를 알 때만 실제로 적용된다.
     * @param sortParam {@code "DISTANCE"} · {@code "FARE"} · {@code "REQUESTED_AT"} 중 하나
     *                 (대소문자 구분). 그 외 값은 {@link IllegalArgumentException}.
     * @return 반경 내 WAITING 배송요청 목록(정렬 적용됨). 대상이 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    public List<RiderDeliveryRequestSummaryResponse> getDeliveryRequests(
            AuthenticatedRider rider, int radiusMeters, String sortParam) {

        requireAvailable(rider);
        requirePositiveRadius(radiusMeters);
        DeliveryRequestSort sort = DeliveryRequestSort.from(sortParam);

        List<DeliveryOrder> waitingOrders = deliveryOrderRepository.findByStatus(OrderStatus.WAITING);
        if (waitingOrders.isEmpty()) {
            return List.of();
        }

        Map<Long, OrderFareSnapshot> estimateByOrderId = loadEstimateSnapshots(waitingOrders);
        Optional<Point> riderPosition = riderGeoRepository.findPosition(rider.memberId());

        List<RiderDeliveryRequestSummaryResponse> summaries = waitingOrders.stream()
                .map(order -> toSummary(order, estimateSnapshotOf(order, estimateByOrderId), riderPosition))
                .filter(summary -> withinRadius(summary, riderPosition, radiusMeters))
                .collect(Collectors.toCollection(ArrayList::new));

        summaries.sort(comparatorFor(sort, riderPosition.isPresent()));
        return summaries;
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
                toFareBreakdownResponse(estimate),
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

        DeliveryOrder assigned = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 배차 확정한 주문을 다시 조회할 수 없습니다. orderId=" + deliveryId));

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

    private FareBreakdownResponse toFareBreakdownResponse(OrderFareSnapshot snapshot) {
        return new FareBreakdownResponse(
                snapshot.getPolicyVersion(),
                snapshot.getCalculationDistanceMeters(),
                snapshot.getBaseFare(),
                snapshot.getDistanceFare(),
                snapshot.getItemSurcharge(),
                snapshot.getTotalFare());
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

    /** DISTANCE 요청인데 위치가 없으면 REQUESTED_AT(오래된 순)으로 대체한다(#55 계약 확정). */
    private Comparator<RiderDeliveryRequestSummaryResponse> comparatorFor(DeliveryRequestSort sort,
                                                                          boolean hasPosition) {
        DeliveryRequestSort effectiveSort = (sort == DeliveryRequestSort.DISTANCE && !hasPosition)
                ? DeliveryRequestSort.REQUESTED_AT
                : sort;

        return switch (effectiveSort) {
            case DISTANCE -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::distanceToPickupMeters);
            case FARE -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::expectedSettlementAmount).reversed();
            case REQUESTED_AT -> Comparator.comparing(RiderDeliveryRequestSummaryResponse::requestedAt);
        };
    }

    private int toMeters(BigDecimal distanceKm) {
        return distanceKm.multiply(METERS_PER_KM).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
