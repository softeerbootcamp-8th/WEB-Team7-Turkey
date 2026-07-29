package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.dto.SettlementListResponse;
import com.turkey.quick.payment.dto.WithdrawalListResponse;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link RiderPointApi} 구현체(골격).
 *
 * <p>구현 규칙은 {@link CustomerPaymentController} 와 동일하다 — 매핑·검증 어노테이션은
 * 인터페이스에만, 반환은 {@code @RestController} 로 JSON.
 */
@RestController
public class RiderPaymentController implements RiderPointApi {

    @Override
    public ApiResponse<PointBalanceResponse> getPointBalance(AuthenticatedRider rider) {
        return null;
    }

    @Override
    public ApiResponse<SettlementListResponse> getSettlements(
            AuthenticatedRider rider, int page, int size) {
        return null;
    }

    @Override
    public ApiResponse<PointTransactionListResponse> getPointTransactions(
            AuthenticatedRider rider, PointTransactionType type, int page, int size) {
        return null;
    }

    @Override
    public ApiResponse<WithdrawalResponse> requestWithdrawal(
            AuthenticatedRider rider, WithdrawalRequest request) {
        return null;
    }

    @Override
    public ApiResponse<WithdrawalListResponse> getWithdrawals(
            AuthenticatedRider rider, int page, int size) {
        return null;
    }
}
