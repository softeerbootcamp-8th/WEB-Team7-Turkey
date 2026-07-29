package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointWallet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointWalletRepository extends JpaRepository<PointWallet, Long> {
}
