package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.customer.dto.CustomerSignupResponse;
import com.turkey.quick.customer.service.CustomerSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/signup")
public class CustomerSignupController implements CustomerSignupApi {

    private final CustomerSignupService customerSignupService;

    @Override
    @PostMapping
    public ApiResponse<CustomerSignupResponse> signup(
            @RequestBody CustomerSignupRequest request) {
        return ApiResponse.ok(CustomerSignupResponse.from(customerSignupService.signup(request)));
    }
}
