package com.turkey.quick.location.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Hidden
@RequestMapping("/api/customer/deliveries")
public interface CustomerTrackingStreamApi {

    @GetMapping(
            value = "/{deliveryId}/tracking/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    SseEmitter subscribeTracking(@PathVariable Long deliveryId);
}