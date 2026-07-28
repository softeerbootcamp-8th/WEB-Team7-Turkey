package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FarePolicyStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarePolicyRepository extends JpaRepository<FarePolicy, Long> {

    /** 동시에 ACTIVE 일 수 있는 정책은 최대 1개다(uk_fare_policy_active). */
    Optional<FarePolicy> findByStatus(FarePolicyStatus status);
}
