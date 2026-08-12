package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.service.RiderLocationService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/location")
public class RiderLocationUpdateController implements RiderLocationUpdateApi {

    private final RiderLocationService riderLocationService;

    @Override
    @PostMapping
    public ApiResponse<Void> updateRiderLocation(
            @Valid @RequestBody RiderLocationUpdateRequest request,
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider) {
        riderLocationService.update(rider, request.toLocationPayload());
        return ApiResponse.ok(null);
    }
}
