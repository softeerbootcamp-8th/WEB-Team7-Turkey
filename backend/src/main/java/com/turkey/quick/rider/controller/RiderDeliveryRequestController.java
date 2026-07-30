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
 * 목록 조회(#55)만 구현한다. 상세(#57)·수락(#56)·넘기기는 각자 이슈에서 채운다
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
    public ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(Long deliveryId) {
        return null;
    }

    @Override
    public ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(Long deliveryId) {
        return null;
    }

    @Override
    public ApiResponse<Void> skipDeliveryRequest(Long deliveryId) {
        return null;
    }
}
