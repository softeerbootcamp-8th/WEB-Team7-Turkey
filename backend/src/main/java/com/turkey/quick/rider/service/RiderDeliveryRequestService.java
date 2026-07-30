package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.DeliveryRequestSort;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
