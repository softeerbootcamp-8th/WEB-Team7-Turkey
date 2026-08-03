package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import com.turkey.quick.rider.service.RiderDeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/deliveries")
public class RiderDeliveryController implements RiderDeliveryApi {

    private final RiderDeliveryService riderDeliveryService;

    @Override
    @GetMapping("/current")
    public ApiResponse<RiderDeliveryResponse> getCurrentDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider) {
        return ApiResponse.ok(riderDeliveryService.getCurrentDelivery(rider));
    }

    @Override
    @PostMapping("/{deliveryId}/transition")
    public ApiResponse<RiderDeliveryResponse> transitionDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @Valid @RequestBody RiderDeliveryTransitionRequest request) {
        return ApiResponse.ok(riderDeliveryService.transition(rider, deliveryId, request.action()));
    }

    @Override
    @PostMapping("/{deliveryId}/complete")
    public ApiResponse<RiderDeliveryCompleteResponse> completeDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @Valid @RequestBody RiderDeliveryCompleteRequest request) {
        return ApiResponse.ok(riderDeliveryService.complete(rider, deliveryId, request));
    }
}
