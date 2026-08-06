package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.DeliveryProof;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, Long> {

    boolean existsByOrder_Id(Long orderId);

    /** 주문당 최대 1건이다(uk_delivery_proof_order). 상세 조회(#46)의 완료 인증 표시에 쓴다. */
    Optional<DeliveryProof> findByOrder_Id(Long orderId);
}
