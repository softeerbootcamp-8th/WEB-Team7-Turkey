package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.service.RiderLocationService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiderLocationUpdateController implements RiderLocationUpdateApi {

    private final RiderLocationService riderLocationService;

    @Override
    public ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request, AuthenticatedRider rider) {
        riderLocationService.update(rider, request.toLocationPayload());
        return ApiResponse.ok(null);
    }
}
