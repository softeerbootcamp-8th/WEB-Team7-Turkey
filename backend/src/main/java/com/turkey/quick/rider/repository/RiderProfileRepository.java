package com.turkey.quick.rider.repository;

import com.turkey.quick.rider.domain.RiderProfile;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderProfileRepository extends JpaRepository<RiderProfile, Long> {

    /** 배송 완료 시 BUSY→AVAILABLE 전이를 다른 상태 변경과 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RiderProfile r where r.memberId = :riderId")
    Optional<RiderProfile> findByIdForUpdate(@Param("riderId") Long riderId);

    /**
     * 배차 확정(#56, ADR-006)의 두 번째 조건부 UPDATE. AVAILABLE인 행만 BUSY로 바꾸고,
     * 영향 행 수(0 또는 1)로 "이 라이더가 지금 배차를 받을 수 있는 상태였는가"를 판정한다.
     * 반드시 {@link com.turkey.quick.order.repository.DeliveryOrderRepository#assignIfWaiting}
     * 다음에 호출한다(ADR-006이 고정한 잠금 순서: 주문 → 라이더, 데드락 회피).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE rider_profile SET operating_status = 'BUSY', status_changed_at = CURRENT_TIMESTAMP(3) "
            + "WHERE member_id = :riderId AND operating_status = 'AVAILABLE'", nativeQuery = true)
    int markBusyIfAvailable(@Param("riderId") Long riderId);
}
