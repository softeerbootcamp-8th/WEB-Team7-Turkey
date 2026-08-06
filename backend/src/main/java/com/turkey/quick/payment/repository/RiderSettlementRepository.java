package com.turkey.quick.payment.repository;

import com.turkey.quick.payment.domain.RiderSettlement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderSettlementRepository extends JpaRepository<RiderSettlement, Long> {

    boolean existsByOrder_Id(Long orderId);

    /**
     * 주문 한 건의 정산 내역. 라이더 운행 기록 상세(#71)가 정산액·정산 시각을 담기 위해 쓴다.
     * 정산은 배송 완료 트랜잭션에서 함께 생성되므로 COMPLETED 주문에는 반드시 존재한다 —
     * 없으면 데이터 불변식 위반(호출자가 500 으로 처리).
     */
    Optional<RiderSettlement> findByOrder_Id(Long orderId);
}
