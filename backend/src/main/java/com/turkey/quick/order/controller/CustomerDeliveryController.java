package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.*;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.order.service.DeliveryTrackingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;


@RequiredArgsConstructor
@RestController
public class CustomerDeliveryController implements CustomerDeliveryApi {

    private final DeliveryService deliveryService;
    private final DeliveryTrackingQueryService deliveryTrackingQueryService;

    @Override
    public ApiResponse<FareQuoteResponse> quoteFare(FareQuoteRequest request) {
        return ApiResponse.ok(deliveryService.quoteFare(request));
    }

    @Override
    public ApiResponse<DeliveryCreateResponse> createDelivery(DeliveryCreateRequest request) {
        return null;
    }

    @Override
    public ApiResponse<DeliveryListResponse> getDeliveries(OrderStatus status, int page, int size) {
        return null;
    }

    @Override
    public ApiResponse<DeliveryDetailResponse> getDelivery(Long deliveryId) {
        return null;
    }

    @Override
    public ApiResponse<DeliveryTrackingResponse> getDeliveryTracking(
            Long deliveryId, AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryTrackingQueryService.getTracking(deliveryId, customer.memberId()));
    }

    @Override
    public ApiResponse<DeliveryCancelResponse> cancelDelivery(Long deliveryId, DeliveryCancelRequest request) {
        return null;
    }
}
