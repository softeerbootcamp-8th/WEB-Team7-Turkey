package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.*;
import com.turkey.quick.order.service.ActiveDeliveryQueryService;
import com.turkey.quick.order.service.DeliveryDetailQueryService;
import com.turkey.quick.order.service.DeliveryListQueryService;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.order.service.DeliveryTrackingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RequiredArgsConstructor
@RestController
@Validated
public class CustomerDeliveryController implements CustomerDeliveryApi {

    private final DeliveryService deliveryService;
    private final DeliveryTrackingQueryService deliveryTrackingQueryService;
    private final DeliveryListQueryService deliveryListQueryService;
    private final DeliveryDetailQueryService deliveryDetailQueryService;
    private final ActiveDeliveryQueryService activeDeliveryQueryService;

    @Override
    public ApiResponse<FareQuoteResponse> quoteFare(FareQuoteRequest request) {
        return ApiResponse.ok(deliveryService.quoteFare(request));
    }

    /**
     * {@code @ResponseStatus} 를 여기에도 둔다. 인터페이스에도 있지만 그건 이 저장소에서 검증된 적이
     * 없는 위치다 — 201 을 실제로 내보내는 다른 API({@code CustomerPaymentController})는 모두 구현체에
     * 달고 있고, {@code references/backend.md} 도 동작에 영향을 주는 애노테이션은 구현체에 두라고 한다.
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeliveryCreateResponse> createDelivery(AuthenticatedCustomer customer,
                                                              DeliveryCreateRequest request) {
        return ApiResponse.ok(deliveryService.createDelivery(request, customer.memberId()));
    }

    @Override
    public ApiResponse<DeliveryListResponse> getDeliveries(OrderStatus status, int page, int size,
                                                            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryListQueryService.getDeliveries(customer.memberId(), status, page, size));
    }

    @Override
    public ApiResponse<ActiveDeliveryResponse> getActiveDelivery(AuthenticatedCustomer customer) {
        return ApiResponse.ok(activeDeliveryQueryService.getActiveDelivery(customer.memberId()));
    }

    @Override
    public ApiResponse<DeliveryDetailResponse> getDelivery(Long deliveryId, AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryDetailQueryService.getDetail(deliveryId, customer.memberId()));
    }

    @Override
    public ApiResponse<DeliveryTrackingResponse> getDeliveryTracking(
            Long deliveryId, AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryTrackingQueryService.getTracking(deliveryId, customer.memberId()));
    }

    @Override
    public ApiResponse<DeliveryCancelResponse> cancelDelivery(Long deliveryId, DeliveryCancelRequest request,
                                                              AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryService.cancelDelivery(deliveryId, customer.memberId(), request.reason()));
    }
}
