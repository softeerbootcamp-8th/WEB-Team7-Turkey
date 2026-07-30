package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointCharge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 승인·실패 전이를 위해 충전 행을 배타 잠금하며 조회한다({@code SELECT ... FOR UPDATE}, #33).
     *
     * <p>잠그지 않으면 같은 충전 건에 승인 요청이 동시에 들어올 때 둘 다 PENDING 을 읽고 각각
     * 승인으로 진행한다. 그러면 잔액이 두 번 늘어날 수 있고, 원장은
     * {@code uk_point_transaction_charge_type} 에 걸려 한쪽이 실패하지만 그건 사후 차단이다.
     * 앞단에서 직렬화해 "포인트는 한 번만 증가"(이슈 비고)를 상태 판정 시점부터 보장한다.
     *
     * <p><b>잠금 순서 규칙</b>: 이 조회가 먼저이고 그다음이
     * {@code PointWalletRepository.findByMemberIdForUpdate} 다. 반대로 잠그는 코드가 생기면 데드락이 난다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PointCharge c where c.id = :pointChargeId")
    Optional<PointCharge> findByIdForUpdate(@Param("pointChargeId") Long pointChargeId);
}
