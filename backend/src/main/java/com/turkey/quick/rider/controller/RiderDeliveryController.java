package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import com.turkey.quick.rider.service.RiderDeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/deliveries")
public class RiderDeliveryController implements RiderDeliveryTransitionApi {

    private final RiderDeliveryService riderDeliveryService;

    @Override
    @PostMapping("/{deliveryId}/transition")
    public ApiResponse<RiderDeliveryResponse> transitionDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @Valid @RequestBody RiderDeliveryTransitionRequest request) {
        return ApiResponse.ok(riderDeliveryService.transition(rider, deliveryId, request.action()));
    }
}
