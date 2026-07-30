package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointCharge;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointChargeRepository extends JpaRepository<PointCharge, Long> {

    /**
     * 멱등키로 기존 충전 요청을 찾는다. {@code uk_point_charge_customer_request} 와 같은 (고객, 키)
     * 조합이라 결과는 0 또는 1건이다.
     *
     * <p>메서드명에 {@code Customer_Id} 로 밑줄을 쓴 이유: {@code PointCharge} 는 {@code customer}
     * (Member 연관)만 갖고 있고 {@code customerId} 스칼라 필드가 없다. 밑줄로 연관 traversal 임을
     * 명시해 프로퍼티 해석이 애매해지지 않게 한다. ({@code PointWalletRepository.findByMemberId} 는
     * {@code PointWallet} 에 실제 {@code memberId} 필드가 있어 밑줄이 필요 없는 경우다.)
     */
    Optional<PointCharge> findByCustomer_IdAndChargeRequestKey(Long customerId, String chargeRequestKey);
}
