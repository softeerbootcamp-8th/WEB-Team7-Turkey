package com.turkey.quick.rider.service;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryItemResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 운행 기록 목록 조회(#70).
 *
 * <p>자신에게 배정되어 완료된 배송만 완료 시각 최신순으로 페이지 조회한다. 금액(정산액·운임)은
 * 담지 않는다 — 배송 기록과 포인트 화면이 분리되어 금액은 포인트 API 소관이다. 그래서 정산
 * 스냅샷 조회 없이 {@code delivery_order} 단일 조회로 끝난다(고객 이용기록과 달리 N+1 운임
 * 배치 조회가 필요 없다).
 */
@Service
@RequiredArgsConstructor
public class RiderDeliveryHistoryService {

    private final DeliveryOrderRepository deliveryOrderRepository;

    @Transactional(readOnly = true)
    public RiderDeliveryHistoryListResponse getDeliveryHistories(Long riderId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "completedAt"));
        Page<DeliveryOrder> result = deliveryOrderRepository
                .findByAssignedRider_MemberIdAndStatus(riderId, OrderStatus.COMPLETED, pageable);

        List<RiderDeliveryHistoryItemResponse> items = result.getContent().stream()
                .map(RiderDeliveryHistoryItemResponse::from)
                .toList();

        return new RiderDeliveryHistoryListResponse(items, page, size, result.getTotalElements());
    }
}
