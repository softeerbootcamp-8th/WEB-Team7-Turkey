package com.turkey.quick.location.controller;

import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Hidden
public interface CustomerTrackingStreamApi {

    SseEmitter subscribeTracking(Long deliveryId,
                                 @Parameter(hidden = true) AuthenticatedCustomer customer);
}
