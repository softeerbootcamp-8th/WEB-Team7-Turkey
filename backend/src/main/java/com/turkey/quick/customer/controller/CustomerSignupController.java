package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.customer.dto.CustomerSignupResponse;
import com.turkey.quick.customer.service.CustomerSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CustomerSignupController implements CustomerSignupApi {

    private final CustomerSignupService customerSignupService;

    @Override
    public ApiResponse<CustomerSignupResponse> signup(CustomerSignupRequest request) {
        return ApiResponse.ok(CustomerSignupResponse.from(customerSignupService.signup(request)));
    }
}
