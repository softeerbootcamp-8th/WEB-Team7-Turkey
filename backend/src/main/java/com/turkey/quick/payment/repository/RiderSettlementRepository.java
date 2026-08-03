package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.RiderSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderSettlementRepository extends JpaRepository<RiderSettlement, Long> {

    boolean existsByOrder_Id(Long orderId);
}
