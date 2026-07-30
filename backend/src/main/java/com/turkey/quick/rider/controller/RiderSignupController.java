package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderSignupRequest;
import com.turkey.quick.rider.dto.RiderSignupResponse;
import com.turkey.quick.rider.service.RiderSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiderSignupController implements RiderSignupApi {

    private final RiderSignupService riderSignupService;

    @Override
    public ApiResponse<RiderSignupResponse> signup(RiderSignupRequest request) {
        return ApiResponse.ok(RiderSignupResponse.from(riderSignupService.signup(request)));
    }
}
