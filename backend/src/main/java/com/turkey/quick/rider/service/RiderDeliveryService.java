package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.DeliveryProof;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.domain.ProofType;
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
import com.turkey.quick.rider.dto.RiderDeliveryCompleteMultipartRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlRequest;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryService {

    /** 완료 인증 사진 허용 크기 상한(잠정값, 10MB). 초과 시 완료 거부 + 객체 삭제. */
    private static final long MAX_PROOF_PHOTO_BYTES = 10L * 1024 * 1024;

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final DeliveryProofRepository deliveryProofRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final RiderSettlementRepository riderSettlementRepository;
    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final RiderDeliveryProofUploadService riderDeliveryProofUploadService;
    private final PlatformTransactionManager transactionManager;

    /** #398: 배송 단계 전이·완료를 고객 추적 SSE로 실시간 전달하기 위해 주입한다. */
    private final TrackingPublisher trackingPublisher;

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
     * ({@link #validateCompletionTarget}), 상태(DELIVERING) 확인(같은 메서드), 인증 형식 검증,
     * 중복 등록 차단(아래 {@code existsByOrder_Id} 검사) — 을 이 완료 트랜잭션이 전부 수행한다.
     *
     * <p>별도 등록 API로 다시 분리하지 않기로 한 이유(사람 확인, 2026-08-04): 이미 병합된 이
     * 완료 트랜잭션의 계약(요청 DTO·검증 순서)을 바꿔야 해서, 리스크 대비 실익이 낮다고 판단했다.
     * 근거는 {@code docs/worklog/2026-08-04-61-delivery-completion-proof.md} 참조.
     *
     * <p><b>완료 엔드포인트는 이제 두 경로를 병행한다(사람 확인).</b> presigned URL 경로
     * ({@link #complete})는 라이더가 {@link #issueProofPhotoUploadUrl}로 발급받은 URL로 S3에
     * 직접 올리고 그 키(proofValue)를 HeadObject로 재검증만 한다. 서버 직접 수신 경로
     * ({@link #completeWithPhotoFile})는 원래 구현대로 file 을 받아 서버가 그 자리에서 S3에
     * 올린다. presign 쪽 예외 상황(고아 객체 정리 등)이 아직 확정되지 않아 부하가 실측되기 전까지
     * 두 경로 모두 살려 둔다 — 별도 URL로 분리했다({@code POST .../complete}는 JSON,
     * {@code POST .../complete/photo-file}는 멀티파트; OpenAPI는 같은 경로에 오퍼레이션을 두 개
     * 못 담아 처음엔 같은 URL+consumes로 시도했다가 springdoc이 하나만 남기는 걸 보고 분리했다).
     * 두 경로 모두 이 메서드가 공유하는 {@link #completeInternal}로 수렴한다.
     */
    @Transactional
    public RiderDeliveryCompleteResponse complete(AuthenticatedRider rider, Long deliveryId,
                                                   RiderDeliveryCompleteRequest request) {
        String proofValue = resolveProofValueFromKey(deliveryId, request.proofType(), request.proofValue());
        return completeInternal(rider, deliveryId, request.proofType(), proofValue);
    }

    /**
     * 서버가 파일을 직접 받아 S3에 올리는 완료 경로(원래 #61 후속 구현, 병행 유지).
     *
     * <p><b>DB 커밋 → S3 업로드 순서다(사람 확인).</b> 원래는 S3 업로드가 먼저였는데, 그 뒤
     * 트랜잭션이 실패하면(정산 등) DB엔 아무 흔적도 없이 S3에만 파일이 남는 완전한 유령
     * 데이터가 됐다. 지금은 배송 완료 트랜잭션(정산·포인트 적립 포함)을 {@link TransactionTemplate}
     * 으로 먼저 커밋시키고 — 이 메서드 자체엔 {@code @Transactional}을 안 붙인다, 같은 빈
     * 안에서 {@link #completeInternal}을 직접 호출하면 AOP 프록시를 안 타 트랜잭션이 안 걸리기
     * 때문이다 — 커밋 후에 S3 업로드를 시도한다. 이러면 S3가 실패해도 배송 완료 자체는
     * 되돌리지 않는다(이미 커밋된 걸 코드로 되돌리려면 별도 보상 트랜잭션이 필요한데, 그럴
     * 경우 결국 배송 완료가 S3 가용성에 다시 종속된다 — 하려는 것과 반대). 대신 실패하면
     * 로그를 남기고 응답의 {@code proofUploadFailed}를 true로 돌려 "완료는 됐지만 사진이
     * 없는 상태"임을 알린다. 재업로드 API는 다음 이슈로 미룬다.
     */
    public RiderDeliveryCompleteResponse completeWithPhotoFile(AuthenticatedRider rider, Long deliveryId,
                                                                 RiderDeliveryCompleteMultipartRequest request) {
        PendingProof pending = resolvePendingProofFromUpload(deliveryId, request);

        RiderDeliveryCompleteResponse response = new TransactionTemplate(transactionManager).execute(status ->
                completeInternal(rider, deliveryId, request.proofType(), pending.proofValue()));

        if (pending.pendingUpload() != null) {
            try {
                riderDeliveryProofUploadService.store(pending.proofValue(), pending.pendingUpload());
            } catch (Exception e) {
                log.error("event=RIDER_DELIVERY_PROOF_PHOTO_UPLOAD_FAILED_AFTER_COMMIT "
                                + "riderId={} orderId={} key={}",
                        rider.memberId(), deliveryId, pending.proofValue(), e);
                return response.withProofUploadFailed(true);
            }
        }
        return response;
    }

    /** 커밋 전엔 키만 정하고, 실제 파일 업로드는 {@link #completeWithPhotoFile}이 커밋 후에 한다. */
    private record PendingProof(String proofValue, MultipartFile pendingUpload) {
    }

    private RiderDeliveryCompleteResponse completeInternal(AuthenticatedRider rider, Long deliveryId,
                                                             ProofType proofType, String proofValue) {
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

        DeliveryProof proof = DeliveryProof.create(order, riderProfile, proofType, proofValue);
        deliveryProofRepository.save(proof);
        order.complete();
        riderProfile.release();

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

        // TrackingPublisher.publishStatus가 이 트랜잭션의 커밋 후로 발행을 미루므로("이른 재조회"
        // 경쟁 방지), 이 호출을 메서드 안 어디에 둬도 안전하다 — 순서를 맞추려고 여기 둔 것이 아니다.
        trackingPublisher.publishStatus(deliveryId, OrderStatus.COMPLETED,
                order.getCompletedAt().toInstant(ZoneOffset.UTC));
        // 완료되면 이 배송으로는 더 보낼 것이 없다 — 연결을 닫아 재연결 → 409 → 재조회 사슬을
        // 즉시 발동시킨다(#450). 이 신호가 유실돼도 emitter 절대 수명(5분)이 같은 사슬을 만들므로
        // 정합성이 아니라 지연(5분 → 약 3초)을 줄이는 최적화다.
        trackingPublisher.publishClose(deliveryId);

        log.info("event=RIDER_DELIVERY_COMPLETED riderId={} orderId={} settlementAmount={}",
                rider.memberId(), deliveryId, settlementAmount);
        return new RiderDeliveryCompleteResponse(
                deliveryId, OrderStatus.COMPLETED, OperatingStatus.AVAILABLE,
                settlementAmount, order.getCompletedAt(), false);
    }

    /**
     * proofType=PHOTO 면 proofValue 는 업로드 URL 발급 API로 미리 받은 저장소 키다. 이 키가
     * 정말 이 배송에 발급된 것인지, 실제로 업로드됐는지, 크기가 상한을 넘지 않는지를 HeadObject로
     * 재검증한다 — presigned PUT URL 자체는 크기를 강제하지 않으므로 이 재검증이 유일한 방어선이다
     * (사람 확인, PUT + 사후검증 전환). 그 외 인증 방식은 클라이언트가 보낸 proofValue 를 그대로
     * 쓴다. 값이 비어 있으면 인증 정보가 없는 것이므로 거부한다.
     */
    private String resolveProofValueFromKey(Long deliveryId, ProofType proofType, String proofValue) {
        if (proofValue == null || proofValue.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "완료 인증 정보(proofValue)가 필요합니다.");
        }
        if (proofType == ProofType.PHOTO) {
            validateUploadedPhoto(deliveryId, proofValue);
        }
        return proofValue;
    }

    /**
     * proofType=PHOTO 면 file 의 키만 미리 만들어 두고(업로드는 안 함 — 커밋 후
     * {@link #completeWithPhotoFile}이 한다), 그 외에는 클라이언트가 보낸 proofValue 를
     * 그대로 쓴다(업로드 대상 없음). 둘 다 없으면 인증 정보가 없는 것이므로 거부한다.
     */
    private PendingProof resolvePendingProofFromUpload(Long deliveryId, RiderDeliveryCompleteMultipartRequest request) {
        if (request.file() != null && !request.file().isEmpty()) {
            String key = riderDeliveryProofUploadService.buildKey(deliveryId, request.file().getOriginalFilename());
            return new PendingProof(key, request.file());
        }
        if (request.proofValue() != null && !request.proofValue().isBlank()) {
            return new PendingProof(request.proofValue(), null);
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST,
                "완료 인증 정보(사진 파일 또는 참조값)가 필요합니다.");
    }

    private void validateUploadedPhoto(Long deliveryId, String key) {
        if (!riderDeliveryProofUploadService.belongsToDelivery(key, deliveryId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "이 배송에 발급된 업로드 키가 아닙니다. key=" + key);
        }
        long sizeBytes = riderDeliveryProofUploadService.sizeOf(key)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "인증 사진이 업로드되지 않았습니다. key=" + key));
        if (sizeBytes > MAX_PROOF_PHOTO_BYTES) {
            riderDeliveryProofUploadService.delete(key);
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "인증 사진 크기가 허용 범위를 초과했습니다. key=" + key + " sizeBytes=" + sizeBytes);
        }
    }

    /**
     * 완료 인증 사진(proofType=PHOTO)을 라이더가 직접 S3에 올릴 수 있는 presigned PUT URL을
     * 발급한다. 배정 라이더·상태(DELIVERING) 검증은 {@link #complete}와 동일한 기준
     * ({@link #validateCompletionTarget})을 쓴다 — 사진은 완료 트랜잭션에 실릴 인증이므로 그
     * 이전에 이미 완료 가능한 상태여야 발급 의미가 있다.
     */
    @Transactional(readOnly = true)
    public RiderDeliveryProofUploadUrlResponse issueProofPhotoUploadUrl(
            AuthenticatedRider rider, Long deliveryId, RiderDeliveryProofUploadUrlRequest request) {
        if (!riderDeliveryProofUploadService.isAllowedContentType(request.contentType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 이미지 형식입니다. contentType=" + request.contentType());
        }

        DeliveryOrder order = deliveryOrderRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "배송요청을 찾을 수 없습니다. deliveryId=" + deliveryId));
        validateCompletionTarget(order, rider.memberId());

        String key = riderDeliveryProofUploadService.buildUploadKey(deliveryId, request.contentType());
        String uploadUrl = riderDeliveryProofUploadService.presignUploadUrl(key, request.contentType());
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plus(RiderDeliveryProofUploadService.UPLOAD_URL_TTL);

        log.info("event=RIDER_DELIVERY_PROOF_UPLOAD_URL_ISSUED riderId={} orderId={} key={}",
                rider.memberId(), deliveryId, key);
        return new RiderDeliveryProofUploadUrlResponse(key, uploadUrl, expiresAt);
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
