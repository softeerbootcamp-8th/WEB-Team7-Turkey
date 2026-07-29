package com.turkey.quick.payment.service;

import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.repository.PointWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class CustomerPaymentService {

    private final PointWalletRepository pointWalletRepository;

    public PointBalanceResponse getPointBalance(Long customerId) {
        PointWallet pointWallet = pointWalletRepository.findByMemberId(customerId)
                .orElseThrow(() -> new IllegalStateException("계좌 정보가 없습니다."));

        return new PointBalanceResponse(pointWallet.getBalance(), pointWallet.getUpdatedAt());
    }
}
