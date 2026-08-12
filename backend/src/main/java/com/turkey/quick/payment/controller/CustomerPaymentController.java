package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeCancelResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.service.CustomerPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/customer/points")
public class CustomerPaymentController implements CustomerPointApi {

    private final CustomerPaymentService customerPaymentService;

    @Override
    @GetMapping
    public ApiResponse<PointBalanceResponse> getPointBalance(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer) {
        return ApiResponse.ok(customerPaymentService.getPointBalance(customer.memberId()));
    }

    @Override
    @GetMapping("/transactions")
    public ApiResponse<PointTransactionListResponse> getPointTransactions(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,

            @RequestParam(name = "type", required = false) PointTransactionType type,

            @RequestParam(name = "page", defaultValue = "0") int page,

            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(
                customerPaymentService.getPointTransactions(customer.memberId(), type, page, size));
    }

    @Override
    @PostMapping("/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PointChargeResponse> requestPointCharge(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,

            @Valid @RequestBody PointChargeRequest request) {
        return ApiResponse.ok(customerPaymentService.chargePointRequest(request, customer.memberId()));
    }

    @Override
    @PostMapping("/charges/{pointChargeId}/confirm")
    public ApiResponse<PointChargeConfirmResponse> confirmPointCharge(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,

            @PathVariable("pointChargeId") Long pointChargeId,

            @Valid @RequestBody PointChargeConfirmRequest request) {
        return ApiResponse.ok(customerPaymentService.confirmPointCharge(
                pointChargeId, request, customer.memberId()));
    }

    @Override
    @PostMapping("/charges/{pointChargeId}/cancel")
    public ApiResponse<PointChargeCancelResponse> cancelPointCharge(
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,

            @PathVariable("pointChargeId") Long pointChargeId) {
        return ApiResponse.ok(customerPaymentService.cancelPointCharge(
                pointChargeId, customer.memberId()));
    }
}
