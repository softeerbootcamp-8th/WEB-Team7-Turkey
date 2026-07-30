package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;


public interface PointWalletRepository extends JpaRepository<PointWallet, Long> {

    Optional<PointWallet> findByMemberId(Long memberId);

    /**
     * 잔액을 변경할 목적으로 지갑 행을 배타 잠금하며 조회한다({@code SELECT ... FOR UPDATE}, #33).
     *
     * <p>충전 승인은 원장에 남길 {@code balanceBefore} 를 알아야 하는데, 조건부 UPDATE
     * ({@code SET balance = balance + :amt})는 영향 행 수만 알려주고 변경 직전 잔액을 주지 않는다.
     * 그래서 "읽고 → 더하고 → 기록"이 필요하고, 그 사이에 다른 트랜잭션이 끼어들면 lost update 가
     * 되므로 읽는 시점에 행을 잠근다({@code @Version} 은 팀 정책상 사용하지 않는다).
     *
     * <p>지갑은 회원당 1행(PK = member_id)이라 잠금 범위가 단일 행이고 회원끼리는 경합하지 않는다.
     * 같은 회원이 자기 지갑을 동시에 여러 번 바꾸는 일은 사실상 없어 병목이 되지 않는다.
     *
     * <p><b>잠금 순서 규칙</b>: 포인트 관련 트랜잭션은 {@code point_charge} → {@code point_wallet}
     * 순서로 잠근다. 순서가 엇갈리면 두 트랜잭션이 서로 상대가 쥔 행을 기다려 데드락이 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PointWallet w where w.memberId = :memberId")
    Optional<PointWallet> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
