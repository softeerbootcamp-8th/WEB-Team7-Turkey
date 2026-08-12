package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    /**
     * 한 충전 건의 특정 유형 원장을 찾는다. {@code uk_point_transaction_charge_type}
     * (point_charge_id, transaction_type) 와 같은 조합이라 결과는 0 또는 1건이다.
     *
     * <p>이미 승인된 충전에 승인 요청이 다시 왔을 때(#33 멱등 재승인) 그때의 잔액을 돌려주기 위해 쓴다.
     * 지갑의 <b>현재</b> 잔액은 그 뒤 다른 거래로 바뀌었을 수 있어 "승인 반영 후 잔액"이 아니다.
     */
    Optional<PointTransaction> findByPointCharge_IdAndTransactionType(
            Long pointChargeId, PointTransactionType transactionType);

    /**
     * 회원 지갑의 거래 내역 전체(유형 미필터, #69). 정렬·페이지는 {@link Pageable}이 담당한다.
     *
     * <p>{@code riderWithdrawal}을 fetch join 하는 이유: WITHDRAWAL 행의 화면 표시가 그 출금의
     * 현재 상태(PENDING/COMPLETED/FAILED)를 필요로 하게 되면서({@code PointTransactionResponse
     * #withdrawalStatus}) 페이지 단위로 라이더 출금을 함께 읽어야 한다. join 없이 매핑하면 행마다
     * lazy 프록시 초기화가 일어나 N+1 이 된다. {@code left} 인 이유는 SETTLEMENT 등 다른 유형은
     * riderWithdrawal 이 null 이기 때문(ck_point_transaction_source).
     */
    @Query("select t from PointTransaction t left join fetch t.riderWithdrawal "
            + "where t.wallet.memberId = :memberId")
    Page<PointTransaction> findByWallet_MemberId(@Param("memberId") Long memberId, Pageable pageable);

    /** 회원 지갑의 거래 내역 중 특정 유형만(#69, 화면 필터 탭). fetch join 이유는 위와 같다. */
    @Query("select t from PointTransaction t left join fetch t.riderWithdrawal "
            + "where t.wallet.memberId = :memberId and t.transactionType = :transactionType")
    Page<PointTransaction> findByWallet_MemberIdAndTransactionType(
            @Param("memberId") Long memberId,
            @Param("transactionType") PointTransactionType transactionType,
            Pageable pageable);
}
