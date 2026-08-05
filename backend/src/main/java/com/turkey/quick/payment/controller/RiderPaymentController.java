package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.dto.SettlementListResponse;
import com.turkey.quick.payment.dto.WithdrawalListResponse;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.payment.service.RiderPaymentService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/rider/points")
public class RiderPaymentController implements RiderPointApi {

    private final RiderPaymentService riderPaymentService;

    @Override
    @GetMapping
    public ApiResponse<PointBalanceResponse> getPointBalance(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider) {
        return ApiResponse.ok(riderPaymentService.getPointBalance(rider.memberId()));
    }

    @Override
    @GetMapping("/settlements")
    public ApiResponse<SettlementListResponse> getSettlements(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @RequestParam(name = "page", defaultValue = "0") int page,

            @RequestParam(name = "size", defaultValue = "20") int size) {
        return null;
    }

    @Override
    @GetMapping("/transactions")
    public ApiResponse<PointTransactionListResponse> getPointTransactions(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @RequestParam(name = "type", required = false) PointTransactionType type,

            @RequestParam(name = "page", defaultValue = "0") int page,

            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(
                riderPaymentService.getPointTransactions(rider.memberId(), type, page, size));
    }

    @Override
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WithdrawalResponse> requestWithdrawal(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Valid @RequestBody WithdrawalRequest request) {
        return ApiResponse.ok(riderPaymentService.requestWithdrawal(rider.memberId(), request));
    }

    @Override
    @GetMapping("/withdrawals")
    public ApiResponse<WithdrawalListResponse> getWithdrawals(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @RequestParam(name = "page", defaultValue = "0") int page,

            @RequestParam(name = "size", defaultValue = "20") int size) {
        return null;
    }
}
