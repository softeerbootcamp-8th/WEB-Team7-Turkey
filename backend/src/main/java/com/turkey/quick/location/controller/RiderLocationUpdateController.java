package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.sse.SseRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiderLocationUpdateController implements RiderLocationUpdateApi {
    private final SseRelay sseRelay;

    @Override
    public ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request) {
        sseRelay.publish(request.deliveryId(), request.toLocationPayload());
        return ApiResponse.ok(null);
    }
}
