package com.turkey.quick.rider.repository;

import com.turkey.quick.rider.domain.RiderWithdrawal;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderWithdrawalRepository extends JpaRepository<RiderWithdrawal, Long> {

    /**
     * 순차 재전송 멱등성(uk_rider_withdrawal_request 와 같은 조합). 이미 있으면 새로 만들지 않고
     * 이 결과를 그대로 돌려준다({@code CustomerPaymentService#chargePointRequest} 와 같은 방식).
     */
    Optional<RiderWithdrawal> findByRider_MemberIdAndRequestKey(Long riderId, String requestKey);

    /**
     * 모의 처리(#90) 전 사전 검증(잠금 없는 읽기)에 쓴다. 소유자 조건을 쿼리에 포함해 "없음"과
     * "타인 것"을 같은 빈 결과로 만든다({@code CustomerPaymentService#verifyConfirmable} 과 같은
     * 판단). 실제 상태 확정은 {@code WithdrawalProcessor} 가 잠금 조회로 다시 한다.
     */
    Optional<RiderWithdrawal> findByIdAndRider_MemberId(Long withdrawalId, Long riderId);

    /**
     * 모의 처리(#90) 대상 출금 행을 소유자 조건과 함께 배타 잠금 조회한다({@code SELECT ... FOR UPDATE}).
     *
     * <p>소유자 조건을 쿼리에 넣어 "없음"과 "타인 것"을 같은 빈 결과로 만든다 — 라이더 운행 기록 상세
     * (#71)와 같은 판단이다. 잠금이 필요한 이유는 같은 출금에 처리 결과가 동시에 두 번 들어올 때
     * 중복 완료·중복 복구를 막기 위해서다.
     *
     * <p><b>잠금 순서</b>: 이 조회가 먼저이고, 실패 처리에서 포인트를 복구할 때만 그다음이
     * {@code PointWalletRepository.findByMemberIdForUpdate} 다({@code PointChargeApprover} 와 같은 순서).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from RiderWithdrawal w where w.id = :withdrawalId and w.rider.memberId = :riderId")
    Optional<RiderWithdrawal> findByIdAndRiderIdForUpdate(
            @Param("withdrawalId") Long withdrawalId, @Param("riderId") Long riderId);
}
