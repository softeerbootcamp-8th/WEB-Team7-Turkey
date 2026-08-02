package com.turkey.quick.location.controller;

import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Hidden
@RequestMapping("/api/customer/deliveries")
public interface CustomerTrackingStreamApi {

    @GetMapping(
            value = "/{deliveryId}/tracking/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    SseEmitter subscribeTracking(@PathVariable Long deliveryId,
                                 @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE) @Parameter(hidden = true) AuthenticatedCustomer customer);
}