package com.turkey.quick.rider.repository;

import com.turkey.quick.rider.domain.RiderPayoutAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderPayoutAccountRepository extends JpaRepository<RiderPayoutAccount, Long> {

    /** PK 가 rider_id 라 findById 로 충분하지만, 호출부에서 의미가 드러나도록 이름을 붙인다. */
    default Optional<RiderPayoutAccount> findByRiderId(Long riderId) {
        return findById(riderId);
    }
}
