package com.turkey.quick.rider.repository;

import com.turkey.quick.rider.domain.RiderWithdrawal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderWithdrawalRepository extends JpaRepository<RiderWithdrawal, Long> {

    /**
     * 순차 재전송 멱등성(uk_rider_withdrawal_request 와 같은 조합). 이미 있으면 새로 만들지 않고
     * 이 결과를 그대로 돌려준다({@code CustomerPaymentService#chargePointRequest} 와 같은 방식).
     */
    Optional<RiderWithdrawal> findByRider_MemberIdAndRequestKey(Long riderId, String requestKey);
}
