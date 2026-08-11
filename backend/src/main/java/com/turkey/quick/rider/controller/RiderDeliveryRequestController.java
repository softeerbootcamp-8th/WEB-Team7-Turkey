package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestCursor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestFilter;
import com.turkey.quick.rider.dto.RiderDeliveryRequestPageResponse;
import com.turkey.quick.order.service.DeliveryTimeoutService;
import com.turkey.quick.rider.service.RiderDeliveryRequestService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/**
 * 목록 조회(#55)·상세 조회(#57)·수락(#56)을 구현한다. 넘기기는 각자 이슈에서 채운다
 * ({@code RiderPaymentController}와 같은 방식으로 나머지는 스켈레톤으로 남긴다).
 *
 * <p>{@code @Validated}는 #367 이 {@code getDeliveryRequests} 의 latitude/longitude 에 인터페이스
 * 단(《{@link RiderDeliveryRequestApi}》)에서 건 {@code @DecimalMin}/{@code @DecimalMax} 를 메서드
 * 파라미터 검증(AOP)으로 활성화하기 위해 추가했다.
 */
@RestController
@RequiredArgsConstructor
@Validated
public class RiderDeliveryRequestController implements RiderDeliveryRequestApi {

    private final RiderDeliveryRequestService riderDeliveryRequestService;

    /**
     * 배차 수락 직전 만료 정리(#42)를 위해 주입한다. 이 호출을 서비스의 {@code @Transactional}
     * 안이 아니라 여기(트랜잭션 밖)서 하는 이유는 커넥션 풀 교착 회피다 — {@code cancelIfExpired}는
     * {@code REQUIRES_NEW}라 accept 트랜잭션이 쥔 커넥션을 정지시킨 채 두 번째 커넥션을 요구하고,
     * 그러면 accept 1건이 커넥션 2개를 동시 점유해 동시 요청이 풀 크기의 절반을 넘으면 교착됐다(#446).
     */
    private final DeliveryTimeoutService deliveryTimeoutService;

    @Override
    public ApiResponse<RiderDeliveryRequestPageResponse> getDeliveryRequests(
            AuthenticatedRider rider, BigDecimal latitude, BigDecimal longitude, int radiusMeters,
            String sort, String sortDirection,
            Long fareMin, Long fareMax, Integer distanceMin, Integer distanceMax,
            int size, Integer afterDistanceMeters, Long afterFare, LocalDateTime afterRequestedAt, Long afterId) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequests(
                rider, latitude, longitude, radiusMeters, sort, sortDirection,
                new RiderDeliveryRequestFilter(fareMin, fareMax, distanceMin, distanceMax),
                size,
                new RiderDeliveryRequestCursor(afterDistanceMeters, afterFare, afterRequestedAt, afterId)));
    }

    @Override
    public ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(
            AuthenticatedRider rider, Long deliveryId) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequest(rider, deliveryId));
    }

    @Override
    public ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(
            AuthenticatedRider rider, Long deliveryId) {
        // 배차 확정 트랜잭션을 열기 전에 만료 정리를 먼저 독립 커밋한다(#42/#446). 만료된 주문이었다면
        // 여기서 CANCELED 로 바뀌어, 아래 수락의 조건부 UPDATE 가 0행이 되고 "이미 취소됨" 409 로 흐른다.
        deliveryTimeoutService.cancelIfExpired(deliveryId);
        return ApiResponse.ok(riderDeliveryRequestService.acceptDeliveryRequest(rider, deliveryId));
    }

    @Override
    public ApiResponse<Void> skipDeliveryRequest(Long deliveryId) {
        return null;
    }
}
