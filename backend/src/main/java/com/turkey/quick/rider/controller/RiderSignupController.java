package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderSignupRequest;
import com.turkey.quick.rider.dto.RiderSignupResponse;
import com.turkey.quick.rider.service.RiderSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/signup")
public class RiderSignupController implements RiderSignupApi {

    private final RiderSignupService riderSignupService;

    @Override
    @PostMapping
    public ApiResponse<RiderSignupResponse> signup(
            @Valid @RequestBody RiderSignupRequest request) {
        return ApiResponse.ok(RiderSignupResponse.from(riderSignupService.signup(request)));
    }
}
