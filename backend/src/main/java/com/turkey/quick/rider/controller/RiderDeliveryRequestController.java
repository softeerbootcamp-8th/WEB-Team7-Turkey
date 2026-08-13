package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.service.DeliveryTimeoutService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestCursor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestFilter;
import com.turkey.quick.rider.dto.RiderDeliveryRequestPageResponse;
import com.turkey.quick.rider.service.RiderDeliveryRequestService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 목록 조회(#55)·상세 조회(#57)·수락(#56)을 구현한다. 넘기기는 각자 이슈에서 채운다
 * ({@code RiderPaymentController}와 같은 방식으로 나머지는 스켈레톤으로 남긴다).
 *
 * <p>{@code @Validated}는 {@link RiderDeliveryRequestApi}에 선언한 latitude/longitude
 * 메서드 파라미터 제약 검증(AOP)을 활성화한다.
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/rider/requests")
public class RiderDeliveryRequestController implements RiderDeliveryRequestApi {

    private final RiderDeliveryRequestService riderDeliveryRequestService;

    /**
     * 배차 수락 직전 만료 정리(#42)를 위해 주입한다. 이 호출을 서비스의 {@code @Transactional}
     * 안이 아니라 여기(트랜잭션 밖)서 하는 이유는 커넥션 풀 교착 회피다 — {@code cancelIfExpired}는
     * {@code REQUIRES_NEW}라 accept 트랜잭션이 쥔 커넥션(C1)을 정지시킨 채 두 번째 커넥션(C2)을 요구한다.
     * 동시 요청이 풀 크기에 도달해 모든 커넥션이 C1로 점유되면 아무도 C2를 얻지 못하고 서로 대기하는
     * 순환 교착에 빠진다(#446). 여유가 하나라도 남으면 C2가 순차 처리돼 배수되므로 임계는 풀 크기다.
     */
    private final DeliveryTimeoutService deliveryTimeoutService;

    @Override
    @GetMapping
    public ApiResponse<RiderDeliveryRequestPageResponse> getDeliveryRequests(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude,
            @RequestParam(defaultValue = "3000") int radiusMeters,
            @RequestParam(defaultValue = "DISTANCE") String sort,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Long fareMin,
            @RequestParam(required = false) Long fareMax,
            @RequestParam(required = false) Integer distanceMin,
            @RequestParam(required = false) Integer distanceMax,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer afterDistanceMeters,
            @RequestParam(required = false) Long afterFare,
            @RequestParam(required = false) LocalDateTime afterRequestedAt,
            @RequestParam(required = false) Long afterId) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequests(
                rider, latitude, longitude, radiusMeters, sort, sortDirection,
                new RiderDeliveryRequestFilter(fareMin, fareMax, distanceMin, distanceMax),
                size,
                new RiderDeliveryRequestCursor(afterDistanceMeters, afterFare, afterRequestedAt, afterId)));
    }

    @Override
    @GetMapping("/{deliveryId}")
    public ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,
            @PathVariable Long deliveryId) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequest(rider, deliveryId));
    }

    @Override
    @PostMapping("/{deliveryId}/accept")
    public ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,
            @PathVariable Long deliveryId) {
        // 배차 확정 트랜잭션을 열기 전에 만료 정리를 먼저 독립 커밋한다(#42/#446). 만료된 주문이었다면
        // 여기서 CANCELED 로 바뀌어, 아래 수락의 조건부 UPDATE 가 0행이 되고 "이미 취소됨" 409 로 흐른다.
        deliveryTimeoutService.cancelIfExpired(deliveryId);
        return ApiResponse.ok(riderDeliveryRequestService.acceptDeliveryRequest(rider, deliveryId));
    }

    @Override
    @PostMapping("/{deliveryId}/skip")
    public ApiResponse<Void> skipDeliveryRequest(@PathVariable Long deliveryId) {
        return null;
    }
}
