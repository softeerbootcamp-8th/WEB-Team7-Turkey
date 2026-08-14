package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.common.routing.RoutingClient.RouteEstimate;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.dto.DeliveryEtaResponse;
import com.turkey.quick.order.dto.RoutePointResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 추적 화면이 주기적으로 부르는 도착 예정 시각 조회(#447).
 *
 * <p><b>추적 게이트({@link DeliveryTrackingAccessService})를 쓰지 않는다.</b> 그 게이트는 종료
 * 상태를 409 로 막는데, 폴링 응답은 200 + null 이어야 한다(사람 확인, 2026-08-10) — 1분마다
 * 도는 요청이 오류를 내면 화면이 정상 상황을 실패로 다루게 된다. 대신 소유권 판정을 쿼리 조건에
 * 두어(없음·타인 것 → 같은 404) 남의 주문 ETA 가 새어 나가는 것만 막는다.
 *
 * <p><b>이 메서드에 {@code @Transactional} 이 없는 것은 의도다.</b> 경로 탐색이 외부 HTTP 를
 * 호출하는데(OSRM 기준 최대 1초: 연결 300ms + 읽기 700ms) 그것을 트랜잭션 안에 두면 외부 서버가
 * 느려지는 만큼 DB 커넥션이 잠긴다 — <b>이 API 는 폴링 경로라 그 비용이 동시 추적 수 × 분당 1회로
 * 곧장 비례한다.</b> 그래서 조회를 먼저 끝내고(그 조회가 자기 트랜잭션) 라우팅은 그 밖에서 한다.
 *
 * <p>그 대신 <b>지연 로딩 연관을 트랜잭션 밖에서 만지면 안 된다</b>(OSIV 가 꺼져 있다) —
 * {@code DeliveryRouteEstimator} 가 읽는 배정 라이더는
 * {@code findWithAssignedRiderByIdAndCustomerId} 가 {@code left join fetch} 로 미리 채운다.
 */
@Service
@RequiredArgsConstructor
public class DeliveryEtaQueryService {

    /**
     * 존재하지 않는 주문과 타인의 주문에 같은 메시지를 쓴다({@link DeliveryTrackingAccessService} 와
     * 같은 이유 — 사유를 나누면 남의 주문 id 를 찍어 보는 것만으로 존재 여부가 새어 나가고,
     * {@code order_id} 는 {@code AUTO_INCREMENT} 라 열거가 쉽다).
     */
    private static final String NOT_FOUND_MESSAGE = "배송요청을 찾을 수 없습니다.";

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final DeliveryRouteEstimator routeEstimator;

    /**
     * @param deliveryId 조회할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @throws BusinessException 404(없음·타인 것). 그 외에는 어떤 실패도 예외로 올리지 않는다 —
     *                           산정 불가는 {@code estimatedArrivalAt = null} 인 정상 응답이다
     */
    public DeliveryEtaResponse getEta(Long deliveryId, Long customerId) {
        DeliveryOrder order = deliveryOrderRepository
                .findWithAssignedRiderByIdAndCustomerId(deliveryId, customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));

        // 추적 불가 상태(WAITING·COMPLETED·CANCELED)는 라우팅을 부르지 않는다. WAITING 은 출발점이
        // 될 라이더가 없고, 종료 상태는 향하는 지점 자체가 없다 — DeliveryRouteEstimator.targetOf
        // 는 그 셋에 IllegalStateException 을 던지므로 여기서 걸러야 한다.
        if (!order.getStatus().isTrackable()) {
            return DeliveryEtaResponse.unavailable(order.getId(), order.getStatus());
        }

        // 여기부터 트랜잭션 밖이다 — 위 조회가 끝난 뒤에 외부 서버를 부른다.
        Optional<RouteEstimate> route = routeEstimator.findRemainingRoute(order);

        return new DeliveryEtaResponse(
                order.getId(),
                order.getStatus(),
                route.map(RouteEstimate::duration).map(DeliveryEtaQueryService::arrivalAt).orElse(null),
                route.map(DeliveryEtaQueryService::toPathResponse).orElse(null));
    }

    private static List<RoutePointResponse> toPathResponse(RouteEstimate route) {
        return route.path().stream().map(RoutePointResponse::from).toList();
    }

    /**
     * 소요 시간을 <b>지금</b>에 더해 도착 예정 시각으로 바꾼다.
     *
     * <p>기준 시각이 라이더의 위치 측정 시각이 아니라 응답 시각인 것은 의도다. 측정 시각을 쓰면
     * 위치가 오래됐을 때(최대 TTL 10분) 이미 지나간 시각을 도착 예정으로 내려보내게 된다. OSRM 은
     * 자유흐름 기준이라 애초에 정밀하지 않은 값이라, 이 근사가 크게 문제되지 않는다.
     */
    private static LocalDateTime arrivalAt(Duration eta) {
        return LocalDateTime.now(ZoneOffset.UTC).plus(eta);
    }
}
