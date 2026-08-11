package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.*;
import com.turkey.quick.order.service.ActiveDeliveryQueryService;
import com.turkey.quick.order.service.DeliveryDetailQueryService;
import com.turkey.quick.order.service.DeliveryEtaQueryService;
import com.turkey.quick.order.service.DeliveryListQueryService;
import com.turkey.quick.order.service.DeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/api/customer/deliveries")
public class CustomerDeliveryController implements CustomerDeliveryApi {

    private final DeliveryService deliveryService;
    private final DeliveryListQueryService deliveryListQueryService;
    private final DeliveryDetailQueryService deliveryDetailQueryService;
    private final ActiveDeliveryQueryService activeDeliveryQueryService;
    private final DeliveryEtaQueryService deliveryEtaQueryService;

    @Override
    @PostMapping("/quote")
    public ApiResponse<FareQuoteResponse> quoteFare(
            @RequestBody FareQuoteRequest request) {
        return ApiResponse.ok(deliveryService.quoteFare(request));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeliveryCreateResponse> createDelivery(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,
            @Valid @RequestBody DeliveryCreateRequest request) {
        return ApiResponse.ok(deliveryService.createDelivery(request, customer.memberId()));
    }

    @Override
    @GetMapping
    public ApiResponse<DeliveryListResponse> getDeliveries(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryListQueryService.getDeliveries(customer.memberId(), status, page, size));
    }

    @Override
    @GetMapping("/active")
    public ApiResponse<ActiveDeliveryResponse> getActiveDelivery(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(activeDeliveryQueryService.getActiveDelivery(customer.memberId()));
    }

    @Override
    @GetMapping("/{deliveryId}")
    public ApiResponse<DeliveryDetailResponse> getDelivery(
            @PathVariable Long deliveryId,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryDetailQueryService.getDetail(deliveryId, customer.memberId()));
    }

    @Override
    @GetMapping("/{deliveryId}/eta")
    public ApiResponse<DeliveryEtaResponse> getDeliveryEta(
            @PathVariable Long deliveryId,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryEtaQueryService.getEta(deliveryId, customer.memberId()));
    }

    @Override
    @PatchMapping("/{deliveryId}/cancel")
    public ApiResponse<DeliveryCancelResponse> cancelDelivery(
            @PathVariable Long deliveryId,
            @Valid @RequestBody DeliveryCancelRequest request,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryService.cancelDelivery(deliveryId, customer.memberId(), request.reason()));
    }
}
