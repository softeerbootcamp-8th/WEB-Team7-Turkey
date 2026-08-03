package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.DeliveryProof;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, Long> {

    boolean existsByOrder_Id(Long orderId);
}
