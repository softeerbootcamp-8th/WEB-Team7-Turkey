package com.turkey.quick.order.service;

import com.turkey.quick.common.routing.Coordinate;
import com.turkey.quick.common.routing.Route;
import com.turkey.quick.common.routing.RoutingClient;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 추적 화면에 실을 "라이더 현재 위치 → 지금 향하는 지점" 경로를 구한다(#421).
 *
 * <p>호출자({@link DeliveryTrackingQueryService})는 지금 {@link Route#duration()} 만 쓰고 경로
 * 폴리라인은 버린다 — 고객에게 ETA 만 보여주기로 했기 때문이다(사람 확인, 2026-08-07). 그래도
 * {@code Route} 를 그대로 돌려주는 것은, 라우팅 호출 한 번에 둘이 함께 오므로 좁혀 봤자 아끼는 것이
 * 없고 화면이 경로선을 요구할 때(#422) 이 클래스를 다시 열지 않아도 되기 때문이다.
 *
 * <p><b>어느 구간인가</b>(사람 확인, 2026-08-07): 픽업 전({@code ASSIGNED}·{@code MOVING_TO_PICKUP})
 * 은 픽업지까지, 픽업 후({@code PICKED_UP}·{@code DELIVERING})는 도착지까지다. 픽업 전에도 최종
 * 도착지까지의 ETA 를 보여 주는 대안은 <b>탈락</b>했다 — 라우팅 호출이 2회로 늘거나 포트에 경유지
 * 인자를 더해야 하는데(#420 재수정), 정작 그렇게 얻은 값도 <b>픽업 소요 시간(수령·대기)을 0으로
 * 두는</b> 낙관적 추정이라 정확해지지 않는다. 대신 프론트는 {@code status} 를 보고 이 ETA 가
 * "픽업 예정"인지 "도착 예정"인지 구분해 표시해야 한다.
 *
 * <p><b>패키지 방향</b>: {@code order} 서비스가 {@code location} 리포지토리를 직접 주입받는다.
 * {@code location → order} 서비스 의존이 이미 있지만({@code CustomerLocationQueryService} →
 * {@code DeliveryTrackingAccessService}), {@link RiderLocationRepository} 는 {@code order} 를 전혀
 * 참조하지 않아 <b>스프링 빈 순환이 생기지 않는다</b> — Boot 3.4 는 빈 순환이면 기동이 실패하므로
 * 그게 실질 기준이다({@code order → payment} 방향을 고정한 것과 같은 이유, CLAUDE.md).
 * 포트 인터페이스를 {@code order} 에 새로 뚫는 대안은 구현체가 하나뿐이라 지금은 간접층만 는다.
 *
 * <p><b>이 클래스는 실패해도 예외를 던지지 않는다.</b> 라우팅 서버 장애·위치 없음·Redis 장애를
 * 전부 빈 값으로 흡수한다 — 추적 화면의 나머지(상태·타임라인·라이더·요금)는 그대로 보여야 한다
 * (#421 확정사항: ETA·경로가 없어도 200).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRouteEstimator {

    private final RoutingClient routingClient;
    private final RiderLocationRepository riderLocationRepository;

    /**
     * @param order 추적 게이트를 통과한 주문(배정 라이더가 반드시 있다)
     * @return 라이더 현재 위치에서 {@link #targetOf(OrderStatus, DeliveryOrder)} 까지의 경로.
     *         라이더 최신 위치가 없거나 경로를 얻지 못하면 빈 값
     */
    public Optional<Route> findRemainingRoute(DeliveryOrder order) {
        Long riderId = order.getAssignedRider().getMemberId();
        Optional<Coordinate> origin = findRiderPosition(riderId);
        if (origin.isEmpty()) {
            // 배차 직후(아직 첫 위치 미전송)나 TTL 10분 만료. 정상적으로 일어나는 상태라 debug 다.
            log.debug("event=ROUTE_ESTIMATE_SKIPPED deliveryId={} riderId={} reason=NO_RIDER_LOCATION",
                    order.getId(), riderId);
            return Optional.empty();
        }
        return routingClient.findRoute(origin.get(), targetOf(order.getStatus(), order));
    }

    /**
     * 라이더 최신 위치(Redis, BUSY 라이더만 저장되고 TTL 10분).
     *
     * <p><b>Redis 장애를 "위치 없음"으로 흡수하는 것이 여기서는 의도다.</b> 폴링 API
     * ({@code CustomerLocationQueryService})는 같은 실패를 503 으로 올리는데, 그쪽은 위치를 주는 것이
     * 유일한 목적이라 실패를 숨기면 화면이 옛 좌표를 계속 믿는다. 반면 추적 스냅샷은 위치가 부수적
     * 입력이라, 여기서 예외를 올리면 상태·타임라인까지 못 그리게 된다.
     *
     * <p>출발점 대체(픽업지 등)는 하지 않는다(사람 확인, 2026-08-07). 배차 직후 라이더가 픽업지에
     * 있다는 보장이 없어 ETA 를 크게 과소평가하고, 픽업 전 상태에서는 출발=도착이 되어
     * <b>"지금 도착"</b> 이라는 명백히 틀린 값이 나온다. 없는 값은 없는 채로 둔다.
     */
    private Optional<Coordinate> findRiderPosition(Long riderId) {
        try {
            return riderLocationRepository.find(riderId)
                    .map(location -> new Coordinate(location.latitude(), location.longitude()));
        } catch (RuntimeException e) {
            log.warn("event=ROUTE_ESTIMATE_LOCATION_FAILED riderId={}", riderId, e);
            return Optional.empty();
        }
    }

    /**
     * 지금 향하는 지점. {@code status} 를 인자로 따로 받는 것은 이 판정만 스프링 없이 단위 테스트하기
     * 위해서다.
     *
     * @throws IllegalStateException 추적 불가 상태({@code WAITING}·{@code COMPLETED}·{@code CANCELED}).
     *                              게이트({@code DeliveryTrackingAccessService})가 409 로 이미 거른다
     */
    static Coordinate targetOf(OrderStatus status, DeliveryOrder order) {
        Address target = switch (status) {
            case ASSIGNED, MOVING_TO_PICKUP -> order.getPickup();
            case PICKED_UP, DELIVERING -> order.getDestination();
            case WAITING, COMPLETED, CANCELED -> throw new IllegalStateException(
                    "추적할 수 없는 상태의 경로를 계산할 수 없습니다. status=" + status);
        };
        return new Coordinate(target.getLatitude(), target.getLongitude());
    }
}
