package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.service.RiderDeliveryRequestService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 목록 조회(#55)·상세 조회(#57)·수락(#56)을 구현한다. 넘기기는 각자 이슈에서 채운다
 * ({@code RiderPaymentController}와 같은 방식으로 나머지는 스켈레톤으로 남긴다).
 */
@RestController
@RequiredArgsConstructor
public class RiderDeliveryRequestController implements RiderDeliveryRequestApi {

    private final RiderDeliveryRequestService riderDeliveryRequestService;

    @Override
    public ApiResponse<List<RiderDeliveryRequestSummaryResponse>> getDeliveryRequests(
            AuthenticatedRider rider, int radiusMeters, String sort) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequests(rider, radiusMeters, sort));
    }

    @Override
    public ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(
            AuthenticatedRider rider, Long deliveryId) {
        return ApiResponse.ok(riderDeliveryRequestService.getDeliveryRequest(rider, deliveryId));
    }

    @Override
    public ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(
            AuthenticatedRider rider, Long deliveryId) {
        return ApiResponse.ok(riderDeliveryRequestService.acceptDeliveryRequest(rider, deliveryId));
    }

    @Override
    public ApiResponse<Void> skipDeliveryRequest(Long deliveryId) {
        return null;
    }
}
