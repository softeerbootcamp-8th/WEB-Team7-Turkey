package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderWithdrawal;
import com.turkey.quick.rider.domain.WithdrawalStatus;
import com.turkey.quick.rider.repository.RiderWithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출금 모의 처리의 <b>DB 트랜잭션 구간</b>만 담당한다(#90) — {@link PointChargeApprover} 와 같은 이유로
 * 분리했다. 은행 이체 호출은 트랜잭션 밖에서 일어나야 하는데({@link RiderPaymentService#processWithdrawal}
 * 주석 참조), 같은 빈 안에서 메서드를 나눠도 self-invocation 은 프록시를 타지 않아
 * {@code @Transactional} 이 걸리지 않는다.
 *
 * <p>메서드가 둘로 나뉜 것도 각각 <b>독립적으로 커밋되어야 하기 때문</b>이다: 성공 확정과 실패 확정은
 * 서로 다른 트랜잭션이고, 실패 확정은 그 안에서 포인트 복구까지 함께 한다(전부 되거나 전부 안 되거나).
 *
 * <p><b>잠금 순서</b>는 {@code rider_withdrawal} → {@code point_wallet} 로 고정한다
 * ({@code point_charge} → {@code point_wallet} 과 같은 순서 규칙).
 */
@RequiredArgsConstructor
@Component
public class WithdrawalProcessor {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalProcessor.class);

    private final RiderWithdrawalRepository riderWithdrawalRepository;
    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /**
     * 이체 식별자를 먼저 커밋한다(상태는 PENDING 유지, V19·#90). {@code PointChargeApprover
     * #recordApprovalReceived} 와 같은 이유다 — 뒤(확정 트랜잭션)가 실패해도 "이체는 됐다"는 사실이
     * DB 에 남는다. 경쟁 요청이 이미 확정을 끝냈으면(PENDING 이 아니면) 아무것도 하지 않는다.
     *
     * <p>이미 <b>다른</b> 식별자가 기록돼 있으면 덮어쓰지 않고 ERROR 로 남긴다 — 같은 출금에 이체
     * 응답이 두 건 존재한다는 뜻이고, 사람이 개입해야 하는 사건이다.
     */
    @Transactional
    public void recordTransferReceived(Long withdrawalId, Long riderId, String providerTransferKey) {
        RiderWithdrawal withdrawal = findForUpdate(withdrawalId, riderId);
        if (withdrawal.getStatus() != WithdrawalStatus.PENDING) {
            return;
        }
        if (!withdrawal.markTransferReceived(providerTransferKey)) {
            log.error("[출금-이중이체] 같은 출금에 서로 다른 이체 응답이 관측됐습니다. 수동 확인 필요. "
                            + "withdrawalId={}, 기록된키={}, 새로받은키={}",
                    withdrawalId, withdrawal.getProviderTransferKey(), providerTransferKey);
        }
    }

    /**
     * 이체 성공 확정: PENDING → COMPLETED. 잔액은 이미 요청 시점에 선차감돼 있어 건드리지 않는다.
     *
     * <p>소유자 조건을 잠금 조회에 포함해 "없음"과 "타인 것"을 같은 404 로 응답한다(#71 과 같은 판단).
     */
    @Transactional
    public RiderWithdrawal complete(Long withdrawalId, Long riderId) {
        RiderWithdrawal withdrawal = findForUpdate(withdrawalId, riderId);
        requirePending(withdrawal);
        withdrawal.complete();
        return withdrawal;
    }

    /**
     * 이체 실패 확정: PENDING → FAILED + 포인트 복구 + WITHDRAWAL_REFUND 원장 1건. 셋은 한 트랜잭션이다.
     *
     * <p>여기서 복구가 실패하면(지갑 없음 등) 예외가 트랜잭션을 롤백시켜 "실패 확정은 됐는데 포인트는
     * 안 돌아온" 상태가 커밋되지 않는다(이슈 예외 처리 절).
     */
    @Transactional
    public RiderWithdrawal fail(Long withdrawalId, Long riderId, String failureReason) {
        RiderWithdrawal withdrawal = findForUpdate(withdrawalId, riderId);
        requirePending(withdrawal);
        withdrawal.fail(failureReason);

        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "포인트 지갑을 찾을 수 없습니다. riderId=" + riderId));

        long balanceBefore = wallet.getBalance();
        wallet.credit(withdrawal.getAmount());
        pointTransactionRepository.save(PointTransaction.forWithdrawal(
                wallet, PointTransactionType.WITHDRAWAL_REFUND, withdrawal.getAmount(), balanceBefore,
                withdrawal.getRequestKey(), withdrawal));

        return withdrawal;
    }

    private RiderWithdrawal findForUpdate(Long withdrawalId, Long riderId) {
        return riderWithdrawalRepository.findByIdAndRiderIdForUpdate(withdrawalId, riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 출금 요청입니다."));
    }

    /**
     * 중복 결과 처리 확인(이슈 처리 흐름 ③). {@link RiderWithdrawal#complete()}·{@link RiderWithdrawal#fail}
     * 도 PENDING 이 아니면 예외를 던지지만 {@code IllegalStateException} 이라 {@code GlobalExceptionHandler}
     * 가 400 으로 뭉갠다 — 여기서 먼저 확인해 "이미 처리된 요청"의 409 의미를 살린다
     * ({@code PointChargeApprover#finalizeApproval} 과 같은 방식).
     */
    private void requirePending(RiderWithdrawal withdrawal) {
        if (withdrawal.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 처리된 출금 요청입니다. status=" + withdrawal.getStatus());
        }
    }
}
