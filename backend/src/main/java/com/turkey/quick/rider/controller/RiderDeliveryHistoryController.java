package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import com.turkey.quick.rider.service.RiderDeliveryHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/history")
public class RiderDeliveryHistoryController implements RiderDeliveryHistoryApi {

    private final RiderDeliveryHistoryService riderDeliveryHistoryService;

    @Override
    @GetMapping
    public ApiResponse<RiderDeliveryHistoryListResponse> getDeliveryHistories(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(riderDeliveryHistoryService.getDeliveryHistories(rider.memberId(), page, size));
    }

    @Override
    @GetMapping("/{deliveryId}")
    public ApiResponse<RiderDeliveryHistoryDetailResponse> getDeliveryHistoryDetail(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId) {
        return ApiResponse.ok(riderDeliveryHistoryService.getDeliveryHistoryDetail(rider.memberId(), deliveryId));
    }
}
