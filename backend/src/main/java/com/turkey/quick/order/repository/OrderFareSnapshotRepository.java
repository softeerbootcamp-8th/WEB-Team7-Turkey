package com.turkey.quick.order.repository;

import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderFareSnapshotRepository extends JpaRepository<OrderFareSnapshot, Long> {

    /** 주문당 fareType 별 최대 1건이라(uk_order_fare_type), orderId 하나에 스냅샷 하나만 매칭된다. */
    List<OrderFareSnapshot> findByOrder_IdInAndFareType(List<Long> orderIds, FareType fareType);
}
