package com.turkey.quick.customer.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.customer.dto.CustomerSessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/session")
public class CustomerSessionController implements CustomerSessionApi {

    @Override
    @GetMapping
    public ApiResponse<CustomerSessionResponse> session(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(CustomerSessionResponse.from(customer));
    }
}
