package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.DeliveryProofRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.domain.RiderSettlement;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.payment.repository.RiderSettlementRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryService {

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final DeliveryProofRepository deliveryProofRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final RiderSettlementRepository riderSettlementRepository;
    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final RiderDeliveryProofUploadService riderDeliveryProofUploadService;

    /** #398: 배송 단계 전이·완료를 고객 추적 SSE로 실시간 전달하기 위해 주입한다. */
    private final TrackingPublisher trackingPublisher;

    /** 새로고침·재로그인 뒤 현재 배송 단계 화면을 복구한다(#86). */
    @Transactional(readOnly = true)
    public RiderDeliveryResponse getCurrentDelivery(AuthenticatedRider rider) {
        List<DeliveryOrder> deliveries = deliveryOrderRepository.findAllInProgressForRider(
                rider.memberId(), OrderStatus.trackableStatuses());

        if (deliveries.size() > 1) {
            log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} reason=MULTIPLE_IN_PROGRESS count={}",
                    rider.memberId(), deliveries.size());
            throw new BusinessException(HttpStatus.CONFLICT,
                    "진행 중 배송이 여러 건 존재합니다. 고객센터에 문의해 주세요.");
        }

        if (deliveries.isEmpty()) {
            if (rider.operatingStatus() == OperatingStatus.BUSY) {
                log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} reason=BUSY_WITHOUT_DELIVERY",
                        rider.memberId());
                throw new BusinessException(HttpStatus.CONFLICT,
                        "배송 수행 상태와 진행 중 배송 정보가 일치하지 않습니다.");
            }
            return null;
        }

        DeliveryOrder order = deliveries.getFirst();
        if (rider.operatingStatus() != OperatingStatus.BUSY) {
            log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} orderId={} reason=DELIVERY_WITHOUT_BUSY status={}",
                    rider.memberId(), order.getId(), rider.operatingStatus());
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 수행 상태와 진행 중 배송 정보가 일치하지 않습니다.");
        }
        if (order.getAssignedRider() == null
                || !order.getAssignedRider().getMemberId().equals(rider.memberId())) {
            log.error("event=RIDER_DELIVERY_CONSISTENCY_ERROR riderId={} orderId={} reason=ASSIGNMENT_MISMATCH",
                    rider.memberId(), order.getId());
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 배정 정보가 현재 라이더와 일치하지 않습니다.");
        }

        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "진행 중 주문의 예상 운임 스냅샷이 없습니다. orderId=" + order.getId()));
        return RiderDeliveryResponse.from(order, estimate.getTotalFare());
    }

    /** 배정된 라이더가 배송 단계를 전이하며, 라이더의 BUSY 상태는 변경하지 않는다. */
    @Transactional
    public RiderDeliveryResponse transition(AuthenticatedRider rider, Long deliveryId,
                                            RiderDeliveryAction action) {
        if (rider.operatingStatus() != OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "배송 수행 중인 라이더만 배송 단계를 변경할 수 있습니다.");
        }
        LocalDateTime transitionedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated;
        OrderStatus previousStatus;
        OrderStatus nextStatus;
        switch (action) {
            case START_MOVING_TO_PICKUP -> {
                updated = deliveryOrderRepository.startMovingToPickupIfAssigned(
                        deliveryId, rider.memberId(), transitionedAt);
                previousStatus = OrderStatus.ASSIGNED;
                nextStatus = OrderStatus.MOVING_TO_PICKUP;
            }
            case PICK_UP -> {
                updated = deliveryOrderRepository.pickUpIfMovingToPickup(
                        deliveryId, rider.memberId(), transitionedAt);
                previousStatus = OrderStatus.MOVING_TO_PICKUP;
                nextStatus = OrderStatus.PICKED_UP;
            }
            case START_DELIVERING -> {
                updated = deliveryOrderRepository.startDeliveringIfPickedUp(
                        deliveryId, rider.memberId(), transitionedAt);
                previousStatus = OrderStatus.PICKED_UP;
                nextStatus = OrderStatus.DELIVERING;
            }
            default -> throw new BusinessException(HttpStatus.CONFLICT,
                    "지원하지 않는 배송 전이 행위입니다. action=" + action);
        }
        if (updated == 0) {
            throw transitionFailure(deliveryId, rider.memberId(), action);
        }

        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 상태를 변경한 주문을 다시 조회할 수 없습니다. orderId=" + deliveryId));
        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(deliveryId, FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "배차된 주문의 예상 운임 스냅샷이 없습니다. orderId=" + deliveryId));

        log.info("event=RIDER_DELIVERY_STATUS_CHANGED riderId={} orderId={} previousStatus={} newStatus={}",
                rider.memberId(), deliveryId, previousStatus, nextStatus);
        trackingPublisher.publishStatus(deliveryId, nextStatus, transitionedAt.toInstant(ZoneOffset.UTC));
        return RiderDeliveryResponse.from(order, estimate.getTotalFare());
    }

    /**
     * 완료 인증 등록, DELIVERING→COMPLETED, 라이더 해제, FINAL 운임·정산·포인트 적립을
     * 하나의 트랜잭션으로 처리한다(#62). 어느 한 단계라도 실패하면 전부 롤백된다.
     *
     * <p><b>이 메서드가 #61(RIDE-QUICK-008, 완료 인증 등록)의 요구사항도 함께 만족한다.</b>
     * #61은 원래 "인증 등록"과 "완료 전이"를 별도 API로 나누도록 설계됐고(#62 이슈 본문도
     * "완료 인증정보 존재 여부 확인"이라고 적어 인증이 먼저 존재한다는 전제였다), 실제로는
     * 두 이슈가 이 메서드 하나로 병합 구현됐다. #61이 요구하는 검증 — 배정 라이더 확인
     * ({@link #validateCompletionTarget}), 상태(DELIVERING) 확인(같은 메서드), 인증 형식 검증
     * ({@link #resolveProofValue}), 중복 등록 차단(아래 {@code existsByOrder_Id} 검사) — 을 이
     * 완료 트랜잭션이 전부 수행한다.
     *
     * <p>별도 등록 API로 다시 분리하지 않기로 한 이유(사람 확인, 2026-08-04): 이미 병합된 이
     * 완료 트랜잭션의 계약(요청 DTO·검증 순서)을 바꿔야 해서, 리스크 대비 실익이 낮다고 판단했다.
     * 사진 업로드(file)도 같은 이유로 별도 엔드포인트로 빼지 않고 이 메서드 안에서 처리한다
     * ({@link RiderDeliveryProofUploadService}). 근거는
     * {@code docs/worklog/2026-08-04-61-delivery-completion-proof.md} 참조.
     */
    @Transactional
    public RiderDeliveryCompleteResponse complete(AuthenticatedRider rider, Long deliveryId,
                                                   RiderDeliveryCompleteRequest request) {
        if (rider.operatingStatus() != OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "배송 수행 중인 라이더만 배송을 완료할 수 있습니다.");
        }

        DeliveryOrder order = deliveryOrderRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "배송요청을 찾을 수 없습니다. deliveryId=" + deliveryId));
        validateCompletionTarget(order, rider.memberId());

        RiderProfile riderProfile = riderProfileRepository.findByIdForUpdate(rider.memberId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "라이더 프로필을 찾을 수 없습니다. riderId=" + rider.memberId()));
        if (riderProfile.getOperatingStatus() != OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 완료 시점의 라이더 상태가 BUSY가 아닙니다. status="
                            + riderProfile.getOperatingStatus());
        }
        if (deliveryProofRepository.existsByOrder_Id(deliveryId)
                || riderSettlementRepository.existsByOrder_Id(deliveryId)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 완료 처리된 배송입니다. deliveryId=" + deliveryId);
        }

        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(deliveryId, FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "완료할 주문의 예상 운임 스냅샷이 없습니다. orderId=" + deliveryId));
        OrderFareSnapshot finalFare = orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                order, estimate.getFarePolicy(), FareType.FINAL, estimate.getPolicyVersion(),
                estimate.getCalculationDistanceMeters(), estimate.getBaseFare(),
                estimate.getDistanceFare(), estimate.getItemSurcharge()));

        String proofValue = resolveProofValue(deliveryId, request);
        DeliveryProof proof = DeliveryProof.create(order, riderProfile, request.proofType(), proofValue);
        deliveryProofRepository.save(proof);
        order.complete();
        riderProfile.release();
        trackingPublisher.publishStatus(deliveryId, OrderStatus.COMPLETED,
                order.getCompletedAt().toInstant(ZoneOffset.UTC));

        long settlementAmount = finalFare.getTotalFare();
        RiderSettlement settlement = riderSettlementRepository.save(
                RiderSettlement.settle(finalFare, settlementAmount));
        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(rider.memberId())
                .orElseThrow(() -> new IllegalStateException(
                        "정산할 라이더의 포인트 지갑이 없습니다. riderId=" + rider.memberId()));
        long balanceBefore = wallet.getBalance();
        wallet.credit(settlementAmount);
        pointTransactionRepository.save(PointTransaction.forSettlement(
                wallet, settlementAmount, balanceBefore, settlementRequestKey(deliveryId), settlement));

        log.info("event=RIDER_DELIVERY_COMPLETED riderId={} orderId={} settlementAmount={}",
                rider.memberId(), deliveryId, settlementAmount);
        return new RiderDeliveryCompleteResponse(
                deliveryId, OrderStatus.COMPLETED, OperatingStatus.AVAILABLE,
                settlementAmount, order.getCompletedAt());
    }

    /**
     * proofType=PHOTO 면 file 을 S3 에 올려 그 키를 인증값으로 쓰고, 그 외에는 클라이언트가 보낸
     * proofValue 를 그대로 쓴다. 둘 다 없으면 인증 정보가 없는 것이므로 거부한다(#61 예외 처리
     * "인증정보 누락").
     */
    private String resolveProofValue(Long deliveryId, RiderDeliveryCompleteRequest request) {
        if (request.file() != null && !request.file().isEmpty()) {
            return riderDeliveryProofUploadService.upload(deliveryId, request.file());
        }
        if (request.proofValue() != null && !request.proofValue().isBlank()) {
            return request.proofValue();
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST,
                "완료 인증 정보(사진 파일 또는 참조값)가 필요합니다.");
    }

    private void validateCompletionTarget(DeliveryOrder order, Long riderId) {
        if (order.getAssignedRider() == null
                || !order.getAssignedRider().getMemberId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "해당 배송에 배정된 라이더만 완료할 수 있습니다.");
        }
        if (order.getStatus() != OrderStatus.DELIVERING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 중인 주문만 완료할 수 있습니다. status=" + order.getStatus());
        }
    }

    private String settlementRequestKey(Long deliveryId) {
        return UUID.nameUUIDFromBytes(("rider-settlement:" + deliveryId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private BusinessException transitionFailure(Long deliveryId, Long riderId, RiderDeliveryAction action) {
        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "배송요청을 찾을 수 없습니다. deliveryId=" + deliveryId));

        if (order.getAssignedRider() == null
                || !order.getAssignedRider().getMemberId().equals(riderId)) {
            return new BusinessException(HttpStatus.FORBIDDEN,
                    "해당 배송에 배정된 라이더만 상태를 변경할 수 있습니다.");
        }
        return new BusinessException(HttpStatus.CONFLICT,
                "요청한 배송 단계로 변경할 수 없는 상태입니다. action=" + action
                        + ", status=" + order.getStatus());
    }
}
