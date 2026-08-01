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
     * 증액으로 진행해 잔액이 두 번 늘 수 있다. 이 잠금이 확정 구간을 직렬화해
     * "포인트는 한 번만 증가"(이슈 비고)를 보장한다.
     *
     * <p><b>PG 호출은 이 잠금 밖에 있다</b>(#33). 그래서 동시 요청이 PG 를 각각 부르는 것은 막지 못하고,
     * 그쪽은 PG 의 멱등성과 확정 구간의 상태 재확인이 담당한다. 잠금 안에서 외부 호출을 하면
     * 응답을 기다리는 동안 DB 커넥션과 행 잠금을 함께 쥐게 되어 트래픽이 몰릴 때 커넥션 풀이 마른다.
     *
     * <p><b>잠금 순서 규칙</b>: 이 조회가 먼저이고 그다음이
     * {@code PointWalletRepository.findByMemberIdForUpdate} 다. 반대로 잠그는 코드가 생기면 데드락이 난다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PointCharge c where c.id = :pointChargeId")
    Optional<PointCharge> findByIdForUpdate(@Param("pointChargeId") Long pointChargeId);
}
