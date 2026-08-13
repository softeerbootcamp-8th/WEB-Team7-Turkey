package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderSessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/session")
public class RiderSessionController implements RiderSessionApi {

    @Override
    @GetMapping
    public ApiResponse<RiderSessionResponse> session(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider) {
        return ApiResponse.ok(RiderSessionResponse.from(rider));
    }
}
