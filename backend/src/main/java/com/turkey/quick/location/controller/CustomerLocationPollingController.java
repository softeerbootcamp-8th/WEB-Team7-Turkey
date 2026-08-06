package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.location.dto.CustomerDeliveryLocationResponse;
import com.turkey.quick.location.service.CustomerLocationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/deliveries")
public class CustomerLocationPollingController implements CustomerLocationPollingApi {

    private final CustomerLocationQueryService customerLocationQueryService;

    @Override
    @GetMapping("/{deliveryId}/location")
    public ApiResponse<CustomerDeliveryLocationResponse> getDeliveryLocation(
            @PathVariable Long deliveryId,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE) AuthenticatedCustomer customer) {
        return ApiResponse.ok(customerLocationQueryService.getLocation(deliveryId, customer.memberId()));
    }
}
