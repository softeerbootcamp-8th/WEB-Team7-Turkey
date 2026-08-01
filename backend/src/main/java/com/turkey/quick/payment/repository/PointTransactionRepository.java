package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
