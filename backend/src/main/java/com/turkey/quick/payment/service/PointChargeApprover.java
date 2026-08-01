package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 충전 승인의 <b>DB 트랜잭션 구간</b>만 담당한다(#33).
 *
 * <p>이 클래스가 따로 있는 이유는 트랜잭션 경계 때문이다. PG 호출은 트랜잭션 밖에서 일어나야 하는데
 * ({@link CustomerPaymentService#confirmPointCharge} 주석 참조), 같은 빈 안에서 메서드를 나눠도
 * self-invocation 은 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않는다. 그래서 트랜잭션이
 * 필요한 구간을 별도 빈으로 뺐다.
 *
 * <p>메서드가 셋으로 나뉜 것도 각각 <b>독립적으로 커밋되어야 하기 때문</b>이다:
 *
 * <ol>
 *   <li>{@link #recordApprovalReceived} — PG 승인 식별자만 먼저 커밋. 뒤가 실패해도 살아남아야 한다
 *   <li>{@link #finalizeApproval} — 상태·잔액·원장을 한 번에. 셋은 전부 되거나 전부 안 되어야 한다
 *   <li>{@link #markFailed} — 거절 확정. 예외로 처리하면 롤백되어 전이가 사라진다
 * </ol>
 *
 * <p><b>잠금 순서</b>는 전 구간에서 {@code point_charge} → {@code point_wallet} 로 고정한다.
 */
@RequiredArgsConstructor
@Component
public class PointChargeApprover {

    private static final Logger log = LoggerFactory.getLogger(PointChargeApprover.class);

    private final PointChargeRepository pointChargeRepository;
    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /**
     * PG 승인 식별자를 먼저 커밋한다(상태는 PENDING 유지).
     *
     * <p>경쟁 요청이 이미 확정을 끝냈다면 아무것도 하지 않는다 — 그 경우 승인 식별자는 이미 기록돼 있다.
     *
     * <p>이미 <b>다른</b> 식별자가 기록돼 있으면 덮어쓰지 않고 ERROR 로 남긴다. 같은 충전에 PG 승인이
     * 두 건 존재한다는 뜻이고, 사람이 개입해 한 건을 취소해야 하는 유일한 사건이다. 승인 API 가
     * 인증 토큰 기준으로 멱등한 벤더라면 두 번째 호출이 거절되므로 이 로그는 찍히지 않는다.
     */
    @Transactional
    public void recordApprovalReceived(Long pointChargeId, String providerPaymentKey) {
        PointCharge pointCharge = findForUpdate(pointChargeId);
        if (pointCharge.getStatus() != PointChargeStatus.PENDING) {
            return;
        }
        if (!pointCharge.markApprovalReceived(providerPaymentKey)) {
            log.error("[결제-이중승인] 같은 충전에 서로 다른 PG 승인이 관측됐습니다. 수동 취소 필요. "
                            + "pointChargeId={}, 기록된키={}, 새로받은키={}",
                    pointChargeId, pointCharge.getProviderPaymentKey(), providerPaymentKey);
        }
    }

    /**
     * 승인 확정: 충전 PENDING→PAID + 지갑 잔액 증가 + CHARGE 원장 1행. 셋은 한 트랜잭션이다.
     *
     * <p>잠금을 잡고 상태를 <b>다시</b> 확인한다. 사전 검증은 PG 호출 전에 잠금 없이 했으므로 그 사이에
     * 경쟁 요청이 확정을 끝냈을 수 있다. 그때는 잔액을 다시 늘리지 않고 멱등 응답을 돌려준다.
     */
    @Transactional
    public PointChargeConfirmResponse finalizeApproval(Long pointChargeId, Long customerId,
                                                       PaymentGateway.Approval approval) {
        PointCharge pointCharge = findForUpdate(pointChargeId);

        if (pointCharge.getStatus() == PointChargeStatus.PAID) {
            return alreadyApprovedResponse(pointCharge);
        }
        if (pointCharge.getStatus() != PointChargeStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "승인할 수 없는 상태입니다. status=" + pointCharge.getStatus());
        }

        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));

        // 원장의 balance_before 는 이 변경 직전의 확정 잔액이어야 한다. 잠금 상태에서 읽었으므로
        // credit 이전 값을 그대로 쓸 수 있다.
        long balanceBefore = wallet.getBalance();

        pointCharge.approve(
                approval.providerPaymentKey(),
                approval.issuerCode(),
                approval.maskedPaymentMethod());
        wallet.credit(pointCharge.getApprovedAmount());
        pointTransactionRepository.save(PointTransaction.forCharge(
                wallet,
                PointTransactionType.CHARGE,
                pointCharge.getApprovedAmount(),
                balanceBefore,
                UUID.randomUUID().toString(),
                pointCharge));

        return new PointChargeConfirmResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getApprovedAmount(),
                wallet.getBalance(),
                pointCharge.getApprovedAt());
    }

    /**
     * PG 거절을 FAILED 로 확정한다. 잔액과 원장은 건드리지 않는다.
     *
     * <p>예외가 아니라 정상 응답으로 돌려주는 이유는 트랜잭션이다 — 예외를 던지면 롤백되어
     * PENDING→FAILED 전이 자체가 사라진다.
     */
    @Transactional
    public PointChargeConfirmResponse markFailed(Long pointChargeId, Long customerId, String reason) {
        PointCharge pointCharge = findForUpdate(pointChargeId);
        if (pointCharge.getStatus() == PointChargeStatus.PENDING) {
            pointCharge.fail(reason);
        }
        return new PointChargeConfirmResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                0L,
                currentBalance(customerId),
                null);
    }

    /**
     * 이미 승인된 충전의 결과를 재구성한다. 잔액은 <b>승인 당시 원장의 balance_after</b> 를 쓴다 —
     * 지갑의 현재 잔액은 그 뒤 다른 거래로 바뀌었을 수 있어 "승인 반영 후 잔액"이 아니다.
     *
     * <p>원장이 없다면 "PAID 인데 잔액이 늘지 않았다"는 뜻이므로 정합성 오류로 다룬다 —
     * 두 변경이 같은 트랜잭션에 있으니 정상 경로에서는 발생할 수 없다.
     */
    @Transactional(readOnly = true)
    public PointChargeConfirmResponse alreadyApprovedResponse(PointCharge pointCharge) {
        long balanceAfter = pointTransactionRepository
                .findByPointCharge_IdAndTransactionType(pointCharge.getId(),
                        PointTransactionType.CHARGE)
                .map(PointTransaction::getBalanceAfter)
                .orElseThrow(() -> new IllegalStateException(
                        "PAID 충전에 CHARGE 원장이 없습니다. pointChargeId=" + pointCharge.getId()));

        return new PointChargeConfirmResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getApprovedAmount(),
                balanceAfter,
                pointCharge.getApprovedAt());
    }

    private PointCharge findForUpdate(Long pointChargeId) {
        return pointChargeRepository.findByIdForUpdate(pointChargeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 충전 요청입니다."));
    }

    private long currentBalance(Long customerId) {
        return pointWalletRepository.findByMemberId(customerId)
                .map(PointWallet::getBalance)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));
    }
}
