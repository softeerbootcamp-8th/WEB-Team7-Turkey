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
import com.turkey.quick.order.service.DeliveryTimeoutService;
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
    private final DeliveryTimeoutService deliveryTimeoutService;
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

    /**
     * 지연 만료 정리(#42)를 생성 트랜잭션 <b>밖</b>에서 먼저 부르는 이유는 커넥션 풀 교착 방지다
     * (#463, #446 형제 버그). {@code createDelivery} 는 {@code @Transactional} 로 커넥션을 쥐는데,
     * 그 안에서 {@code REQUIRES_NEW} 인 {@code expireIfStale} 를 부르면 요청 1건이 커넥션 2개를
     * 동시에 점유한다. 여기서 먼저 호출하면 만료 정리(자기 트랜잭션에서 커밋·반납)와 생성이 커넥션을
     * 순차로만 쓴다. {@code expireIfStale} 의 독립 커밋 의미(#42)는 그대로다 — 정리가 성립한 뒤 생성이
     * 실패해도 되돌아가지 않는다.
     */
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeliveryCreateResponse> createDelivery(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,
            @RequestBody DeliveryCreateRequest request) {
        deliveryTimeoutService.expireIfStale(customer.memberId());
        return ApiResponse.ok(deliveryService.createDelivery(request, customer.memberId()));
    }

    @Override
    @GetMapping
    public ApiResponse<DeliveryListResponse> getDeliveries(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
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
            @RequestBody DeliveryCancelRequest request,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(deliveryService.cancelDelivery(deliveryId, customer.memberId(), request.reason()));
    }
}
