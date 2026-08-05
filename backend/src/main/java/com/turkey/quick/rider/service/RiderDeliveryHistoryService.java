package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.DeliveryProofRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.RiderSettlement;
import com.turkey.quick.payment.repository.RiderSettlementRepository;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryItemResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 운행 기록 조회(#70 목록, #71 상세).
 *
 * <p>목록은 자신에게 배정되어 완료된 배송만 완료 시각 최신순으로 페이지 조회하며, 금액은 담지 않는다
 * (배송 기록과 포인트 화면이 분리되어 금액은 포인트 API 소관). 상세는 이슈 #71 명세에 따라 확정 운임
 * (FINAL 스냅샷)과 정산 내역을 함께 담는다 — 상세는 "왜 이 금액인지"를 설명하는 정산 확인 화면이라
 * 목록의 "금액 제외" 정책과 갈린다(사람 확인, 2026-08-05).
 */
@Service
@RequiredArgsConstructor
public class RiderDeliveryHistoryService {

    /**
     * 존재하지 않는 주문·타인의 주문·완료되지 않은 주문에 같은 메시지를 쓴다(고객 상세와 같은 이유 —
     * 사유를 나누면 남의 주문 id 를 찍어 보는 것만으로 존재 여부가 새어 나간다).
     */
    private static final String NOT_FOUND_MESSAGE = "운행 기록을 찾을 수 없습니다.";

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final RiderSettlementRepository riderSettlementRepository;
    private final DeliveryProofRepository deliveryProofRepository;
    private final RiderDeliveryProofStorage riderDeliveryProofStorage;

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

    /**
     * 운행 기록 상세(#71). 배정 조건을 쿼리에 두어 없음/타인 것을 같은 404 로 응답하고, 상태를
     * COMPLETED 로 좁혀 진행 중 주문 id 는 404 로 막는다. 그 조건이 곧 아래 두 조회(FINAL 스냅샷,
     * 정산 내역)의 존재 불변식을 보장한다 — 없으면 클라이언트가 고칠 수 없는 데이터 손상이라 500 이다.
     *
     * @param riderId    세션에서 얻은 라이더 식별자
     * @param deliveryId 조회할 운행 기록
     * @throws BusinessException 404(없음·타인 것·미완료) / 500(운임 스냅샷 또는 정산 내역 없음)
     */
    @Transactional(readOnly = true)
    public RiderDeliveryHistoryDetailResponse getDeliveryHistoryDetail(Long riderId, Long deliveryId) {
        DeliveryOrder order = deliveryOrderRepository
                .findByIdAndAssignedRider_MemberIdAndStatus(deliveryId, riderId, OrderStatus.COMPLETED)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));

        OrderFareSnapshot finalSnapshot = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), FareType.FINAL)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "완료 배송에 최종 운임 스냅샷이 없습니다. deliveryId=" + order.getId()));

        RiderSettlement settlement = riderSettlementRepository.findByOrder_Id(order.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "완료 배송에 정산 내역이 없습니다. deliveryId=" + order.getId()));

        DeliveryProof proof = deliveryProofRepository.findByOrder_Id(order.getId()).orElse(null);
        String proofValue = resolveProofValue(proof);

        return RiderDeliveryHistoryDetailResponse.from(
                order, FareBreakdownResponse.from(finalSnapshot), settlement, proof, proofValue);
    }

    /**
     * proofType=PHOTO 면 delivery_proof.proof_value 는 업로드 키일 뿐이라, 응답에는 저장소가 해석한
     * 실제 경로(로컬 절대경로 / S3 객체 URL)를 담는다. 그 외 타입은 등록값이 곧 최종 표시값이라 그대로
     * 쓴다(고객 상세 {@code DeliveryDetailQueryService.resolveProofValue}와 같은 규칙).
     */
    private String resolveProofValue(DeliveryProof proof) {
        if (proof == null) {
            return null;
        }
        if (proof.getProofType() == ProofType.PHOTO) {
            return riderDeliveryProofStorage.resolvePath(proof.getProofValue());
        }
        return proof.getProofValue();
    }
}
