package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderSessionResponse;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiderSessionController implements RiderSessionApi {

    @Override
    public ApiResponse<RiderSessionResponse> session(AuthenticatedRider rider) {
        return ApiResponse.ok(RiderSessionResponse.from(rider));
    }
}
