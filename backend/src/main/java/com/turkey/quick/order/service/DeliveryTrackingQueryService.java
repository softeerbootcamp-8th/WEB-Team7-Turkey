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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송 추적 화면 진입 시 한 번 그릴 스냅샷(#79).
 *
 * <p><b>스트림과 판정을 공유한다.</b> 404(없음·타인 주문)/409(추적 불가 상태)를
 * {@link DeliveryTrackingAccessService}에 위임하므로 <b>이 API 가 200 이면 SSE 스트림도 열린다</b>가
 * 성립한다. 프론트가 이 보장에 기대는 이유는 브라우저 {@code EventSource} 가 상태코드와 본문을
 * 스크립트에 노출하지 않아, 스트림 실패 사유를 알 수 있는 통로가 이 REST 뿐이라는 것이다
 * ({@code docs/04-frontend-api-map.md} §7).
 *
 * <p><b>주문 행을 두 번 읽는다</b> — 게이트가 투영을 읽고(SSE 는 트랜잭션 밖에서 값을 쓰므로
 * 투영이어야 한다) 여기서 엔터티를 다시 읽는다. 판정 정책을 복사하지 않는 대가이고, 화면 진입당
 * 1회뿐이라 감내한다. 문제가 되면 투영 쿼리 하나로 합친다(그때 게이트 정책도 함께 옮겨야 한다).
 */
@Service
@RequiredArgsConstructor
public class DeliveryTrackingQueryService {

    private final DeliveryTrackingAccessService accessService;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;

    /**
     * @param deliveryId 조회할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @throws BusinessException 404(없음·타인 것) / 409(WAITING·COMPLETED·CANCELED) /
     *                           500(예상 운임 스냅샷 없음)
     */
    @Transactional(readOnly = true)
    public DeliveryTrackingResponse getTracking(Long deliveryId, Long customerId) {
        accessService.authorizeTracking(deliveryId, customerId);

        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                // 게이트가 같은 트랜잭션에서 이미 읽은 행이라 도달하지 않는다.
                .orElseThrow(() -> new IllegalStateException(
                        "추적 게이트를 통과한 주문을 다시 조회할 수 없습니다. deliveryId=" + deliveryId));

        // 추적 가능 상태에서는 라이더가 반드시 있다(DDL ck_delivery_assignment). 지연 로딩이지만
        // 이 메서드가 트랜잭션 경계라 안전하다 — OSIV 가 꺼져 있어 응답 조립을 여기서 끝내야 한다.
        Member rider = order.getAssignedRider().getMember();

        return new DeliveryTrackingResponse(
                order.getId(),
                order.getStatus(),
                DeliveryStatusStepResponse.timelineOf(order),
                rider.getName(),
                rider.getPhoneNumber(),
                // 산정 근거가 없어 항상 null 이다(사람 확인, 2026-07-31). 지도·경로 API 가 붙을 때 채운다.
                null,
                totalFare(order));
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
