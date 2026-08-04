package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.dto.DeliveryDetailResponse;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.DeliveryProofRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송요청 상세 조회(#46). 추적 스냅샷(#79, {@link DeliveryTrackingQueryService})과 달리 상태를
 * 가리지 않는다 — WAITING 부터 CANCELED 까지 고객 본인 주문이면 전부 200 이다.
 */
@Service
@RequiredArgsConstructor
public class DeliveryDetailQueryService {

    /**
     * 존재하지 않는 주문과 타인의 주문에 같은 메시지를 쓴다(DeliveryTrackingAccessService 와 같은
     * 이유 — 사유를 나누면 남의 주문 id 를 찍어 보는 것만으로 존재 여부가 새어 나간다).
     */
    private static final String NOT_FOUND_MESSAGE = "배송요청을 찾을 수 없습니다.";

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final DeliveryProofRepository deliveryProofRepository;
    private final RiderDeliveryProofStorage riderDeliveryProofStorage;

    /**
     * @param deliveryId 조회할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @throws BusinessException 404(없음·타인 것) / 500(운임 스냅샷 없음 — 데이터 불변식 위반)
     */
    @Transactional(readOnly = true)
    public DeliveryDetailResponse getDetail(Long deliveryId, Long customerId) {
        DeliveryOrder order = deliveryOrderRepository.findByIdAndCustomer_Id(deliveryId, customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));

        FareBreakdownResponse fare = fare(order);
        DeliveryProof proof = deliveryProofRepository.findByOrder_Id(order.getId()).orElse(null);
        String proofValue = resolveProofValue(proof);

        // 지연 로딩인 assignedRider.member 접근이 여기서 끝나야 한다(OSIV off, 트랜잭션 경계는 이 메서드).
        return DeliveryDetailResponse.from(order, fare, proof, proofValue);
    }

    /**
     * proofType=PHOTO 면 delivery_proof.proof_value 에 저장된 건 업로드 키일 뿐이라, 응답에는
     * {@link RiderDeliveryProofStorage}가 해석한 실제 저장 경로(로컬 절대경로 / S3 객체 URL)를
     * 담는다. 그 외 타입은 등록값 자체가 이미 최종 표시값이라 그대로 쓴다.
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

    /**
     * 완료 주문은 FINAL, 그 외는 ESTIMATE 스냅샷을 쓴다(DTO 계약). 없으면 400 이 아니라 500 이다 —
     * 클라이언트가 고칠 수 있는 문제가 아니라 데이터 불변식이 깨진 상태이기 때문이다
     * (DeliveryTrackingQueryService.totalFare 와 같은 판단).
     */
    private FareBreakdownResponse fare(DeliveryOrder order) {
        FareType fareType = order.getStatus() == OrderStatus.COMPLETED ? FareType.FINAL : FareType.ESTIMATE;
        OrderFareSnapshot snapshot = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), fareType)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "배송요청에 운임 스냅샷이 없습니다. deliveryId=" + order.getId() + ", fareType=" + fareType));

        return new FareBreakdownResponse(
                snapshot.getPolicyVersion(),
                snapshot.getCalculationDistanceMeters(),
                snapshot.getBaseFare(),
                snapshot.getDistanceFare(),
                snapshot.getItemSurcharge(),
                snapshot.getTotalFare());
    }
}
