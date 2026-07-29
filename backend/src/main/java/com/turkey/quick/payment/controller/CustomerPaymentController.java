package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.service.CustomerPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class CustomerPaymentController implements CustomerPointApi {

    private final CustomerPaymentService customerPaymentService;

    @Override
    public ApiResponse<PointBalanceResponse> getPointBalance(AuthenticatedCustomer customer) {
        return ApiResponse.ok(customerPaymentService.getPointBalance(customer.memberId()));
    }

    @Override
    public ApiResponse<PointTransactionListResponse> getPointTransactions(
            AuthenticatedCustomer customer, PointTransactionType type, int page, int size) {
        return null;
    }

    @Override
    public ApiResponse<PointChargeResponse> requestPointCharge(
            AuthenticatedCustomer customer, PointChargeRequest request) {
        return null;
    }

    @Override
    public ApiResponse<PointChargeConfirmResponse> confirmPointCharge(
            AuthenticatedCustomer customer, Long pointChargeId, PointChargeConfirmRequest request) {
        return null;
    }
}
