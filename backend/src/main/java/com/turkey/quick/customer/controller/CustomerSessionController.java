package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.dto.CustomerSessionResponse;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerSessionController implements CustomerSessionApi {

    @Override
    public ApiResponse<CustomerSessionResponse> session(AuthenticatedCustomer customer) {
        return ApiResponse.ok(CustomerSessionResponse.from(customer));
    }
}
