package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.DeliveryListResponse;
import com.turkey.quick.order.dto.DeliverySummaryResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 이용기록 목록 조회(#43). MVP 는 기간·상태 필터 중 상태만 제공하고, 정렬은 요청 시각
 * 최신순으로 고정한다(이슈 비고).
 */
@Service
@RequiredArgsConstructor
public class DeliveryListQueryService {

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Transactional(readOnly = true)
    public DeliveryListResponse getDeliveries(Long customerId, OrderStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<DeliveryOrder> result = status == null
                ? deliveryOrderRepository.findByCustomer_Id(customerId, pageable)
                : deliveryOrderRepository.findByCustomer_IdAndStatus(customerId, status, pageable);

        Map<Long, Long> fareByOrderId = fareByOrderId(result.getContent());
        List<DeliverySummaryResponse> items = result.getContent().stream()
                .map(order -> DeliverySummaryResponse.from(order, fareByOrderId.get(order.getId())))
                .toList();

        return new DeliveryListResponse(items, page, size, result.getTotalElements());
    }

    /**
     * N+1 을 피하려고 완료/미완료로 나눠 두 번의 배치 조회로 끝낸다
     * ({@link OrderFareSnapshotRepository#findByOrder_IdInAndFareType} 는 이 목록 조회를 위해
     * 미리 만들어져 있었다). 없으면 데이터 불변식 위반이라 500 이다
     * (DeliveryTrackingQueryService.totalFare 와 같은 판단) — 목록 전체를 실패시키는 대신 항목별로
     * 조용히 null 을 내보내면 결제 금액이 빠진 이용기록이 화면에 나갈 수 있다.
     */
    private Map<Long, Long> fareByOrderId(List<DeliveryOrder> orders) {
        List<Long> completedIds = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .map(DeliveryOrder::getId)
                .toList();
        List<Long> otherIds = orders.stream()
                .filter(order -> order.getStatus() != OrderStatus.COMPLETED)
                .map(DeliveryOrder::getId)
                .toList();

        Map<Long, Long> fareByOrderId = new HashMap<>();
        putFares(fareByOrderId, completedIds, FareType.FINAL);
        putFares(fareByOrderId, otherIds, FareType.ESTIMATE);

        for (DeliveryOrder order : orders) {
            if (!fareByOrderId.containsKey(order.getId())) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "배송요청에 운임 스냅샷이 없습니다. deliveryId=" + order.getId());
            }
        }
        return fareByOrderId;
    }

    private void putFares(Map<Long, Long> fareByOrderId, List<Long> orderIds, FareType fareType) {
        if (orderIds.isEmpty()) {
            return;
        }
        for (OrderFareSnapshot snapshot
                : orderFareSnapshotRepository.findByOrder_IdInAndFareType(orderIds, fareType)) {
            fareByOrderId.put(snapshot.getOrder().getId(), snapshot.getTotalFare());
        }
    }
}
