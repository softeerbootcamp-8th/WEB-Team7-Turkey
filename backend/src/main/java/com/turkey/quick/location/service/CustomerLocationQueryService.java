package com.turkey.quick.location.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.CustomerDeliveryLocationResponse;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.service.DeliveryTrackingAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 고객 위치 폴링 API(#311, 부하테스트용 실험 arm)의 조회 로직.
 *
 * <p>인가·소유권 판정은 새로 만들지 않고 SSE·{@code /tracking} 스냅샷과 같은
 * {@link DeliveryTrackingAccessService#authorizeTracking}을 그대로 쓴다 — 404(없음·타인 것)/409
 * (추적 불가 상태) 시맨틱이 세 경로 모두 같아야 부하테스트에서 SSE와 폴링을 공정하게 비교할 수 있다.
 *
 * <p><b>이 클래스가 하는 유일한 새 일은 Redis 조회 실패를 "위치 없음"으로 위장하지 않는 것이다</b>
 * (이슈 예외 흐름). {@link RiderLocationRepository#find}가 던지는 런타임 예외(연결 실패 등)를 503으로
 * 바꾼다 — 그 외의 실패(값 없음, 형식 손상)는 리포지토리 안에서 이미 빈 값으로 흡수돼 여기 도달하지
 * 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerLocationQueryService {

    private final DeliveryTrackingAccessService accessService;
    private final RiderLocationRepository riderLocationRepository;

    /**
     * @param deliveryId 조회할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @throws BusinessException 404(없음·타인 것) / 409(추적 불가 상태) / 503(Redis 조회 실패)
     */
    public CustomerDeliveryLocationResponse getLocation(Long deliveryId, Long customerId) {
        TrackableDelivery delivery = accessService.authorizeTracking(deliveryId, customerId);

        // WAITING은 구독은 허용되지만(#401) 라이더가 아직 없어 riderId가 null이다 — 조회할
        // 대상이 없으니 Redis를 보지 않고 바로 "위치 없음"으로 답한다.
        LocationPayload location = delivery.riderId() == null ? null : findLocationOrFail(delivery.riderId());

        return new CustomerDeliveryLocationResponse(delivery.orderId(), delivery.status(), location);
    }

    private LocationPayload findLocationOrFail(Long riderId) {
        try {
            return riderLocationRepository.find(riderId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("event=CUSTOMER_LOCATION_POLL_REDIS_FAILED riderId={}", riderId, e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "위치 조회에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
