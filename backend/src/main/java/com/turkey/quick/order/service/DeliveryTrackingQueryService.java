package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.dto.DeliveryStatusStepResponse;
import com.turkey.quick.order.dto.DeliveryTrackingResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 배송 추적 화면 진입 시 한 번 그릴 스냅샷(#79) + 도착 예정 시각(#421).
 *
 * <p>고객에게는 ETA 만 보여준다(사람 확인, 2026-08-07). 자세한 근거는
 * {@link DeliveryTrackingResponse} javadoc.
 *
 * <p><b>스트림과 판정을 공유한다.</b> 404(없음·타인 주문)/409(추적 불가 상태)를
 * {@link DeliveryTrackingAccessService}에 위임하므로 <b>이 API 가 200 이면 SSE 스트림도 열린다</b>가
 * 성립한다. 프론트가 이 보장에 기대는 이유는 브라우저 {@code EventSource} 가 상태코드와 본문을
 * 스크립트에 노출하지 않아, 스트림 실패 사유를 알 수 있는 통로가 이 REST 뿐이라는 것이다
 * ({@code docs/04-frontend-api-map.md} §7).
 *
 * <p><b>이 메서드에 트랜잭션이 없는 것은 #421 의 결과다.</b> ETA 산정이 경로 탐색 API 를 호출하는데
 * (OSRM 기준 최대 1초: 연결 300ms + 읽기 700ms) 그 호출을 트랜잭션 안에 두면 외부 서버가 느려지는 만큼 DB 커넥션이
 * 잠긴다 — 백업 폴링이 1분 주기(#422)라 동시 추적 수에 그대로 비례한다. 그래서 DB 읽기를 먼저 끝내고
 * (각 조회가 자기 트랜잭션) 라우팅 호출은 그 밖에서 한다. 대가로 세 번의 읽기가 같은 스냅샷을 보지
 * 않지만, 표시용 조회라 감내한다(원래도 주문 행을 두 번 읽고 있었다).
 *
 * <p>그 대신 <b>지연 로딩 연관을 트랜잭션 밖에서 만지면 안 된다</b>(OSIV 가 꺼져 있다) — 라이더·회원은
 * {@code findWithAssignedRiderById} 가 {@code join fetch} 로 미리 채운다.
 */
@Service
@RequiredArgsConstructor
public class DeliveryTrackingQueryService {

    private final DeliveryTrackingAccessService accessService;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final DeliveryRouteEstimator routeEstimator;

    /**
     * @param deliveryId 조회할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @throws BusinessException 404(없음·타인 것) / 409(WAITING·COMPLETED·CANCELED) /
     *                           500(예상 운임 스냅샷 없음)
     */
    public DeliveryTrackingResponse getTracking(Long deliveryId, Long customerId) {
        accessService.authorizeTracking(deliveryId, customerId);

        DeliveryOrder order = deliveryOrderRepository.findWithAssignedRiderById(deliveryId)
                // 게이트가 방금 읽은 행이라 도달하지 않는다.
                .orElseThrow(() -> new IllegalStateException(
                        "추적 게이트를 통과한 주문을 다시 조회할 수 없습니다. deliveryId=" + deliveryId));

        // 추적 가능 상태에서는 라이더가 반드시 있다(DDL ck_delivery_assignment).
        Member rider = order.getAssignedRider().getMember();
        Long totalFare = totalFare(order);

        // 여기부터 트랜잭션 밖이다 — 위 조회가 모두 끝난 뒤에 외부 서버를 부른다.
        Optional<Duration> eta = routeEstimator.findRemainingRoute(order);

        return new DeliveryTrackingResponse(
                order.getId(),
                order.getStatus(),
                DeliveryStatusStepResponse.timelineOf(order),
                rider.getName(),
                rider.getPhoneNumber(),
                eta.map(DeliveryTrackingQueryService::arrivalAt).orElse(null),
                totalFare);
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

    /**
     * 예상 운임만 본다. 최종 운임(FINAL)은 완료 시점에 생기는데 완료된 배송은 게이트가 409 로
     * 거르므로 이 경로에 오지 않는다.
     *
     * <p>없으면 400 이 아니라 500 이다 — 주문에 예상 운임이 없는 것은 클라이언트가 고칠 수 있는
     * 문제가 아니라 데이터 불변식이 깨진 상태다(라이더 상세는 여기서 {@code IllegalStateException}
     * 을 쓰는데, 그건 {@code GlobalExceptionHandler} 가 400 으로 바꿔 사유를 오해하게 만든다).
     */
    private Long totalFare(DeliveryOrder order) {
        return orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), FareType.ESTIMATE)
                .map(OrderFareSnapshot::getTotalFare)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "배송요청에 예상 운임 정보가 없습니다. deliveryId=" + order.getId()));
    }
}
