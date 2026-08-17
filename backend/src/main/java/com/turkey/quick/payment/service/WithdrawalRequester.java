package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.domain.RiderWithdrawal;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.rider.repository.RiderWithdrawalRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출금 신청의 <b>DB 트랜잭션 구간</b>만 담당한다(#68). {@link PointChargePreparer} 와 같은 구조이고
 * 존재 이유도 같다 — 유니크 위반으로 rollback-only 가 된 트랜잭션 안에서는 이긴 건을 재조회할 수
 * 없으므로, 저장과 복구 조회가 <b>서로 다른 트랜잭션</b>이어야 한다.
 *
 * <p>충전 준비와 다른 점은 <b>잔액이 함께 움직인다</b>는 것이다(선차감 모델). 그래서 롤백이
 * 정합성의 일부다 — 진 요청이 방금 debit 한 잔액은 유니크 위반과 함께 되돌아간다.
 */
@RequiredArgsConstructor
@Component
public class WithdrawalRequester {

    private final PointWalletRepository pointWalletRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final RiderWithdrawalRepository riderWithdrawalRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /**
     * 잔액을 선차감하고 PENDING 출금 건과 WITHDRAWAL 원장을 남긴다.
     *
     * <p>선조회는 <b>순차</b> 재전송을 흡수한다. 동시 재전송은 {@code uk_rider_withdrawal_request} 가
     * 판정하고, 그 예외는 잡지 않고 호출자({@link RiderPaymentService#requestWithdrawal})까지 올린다.
     *
     * <p><b>지갑 잠금 때문에 두 요청은 애초에 직렬화된다.</b> 진 쪽은 이긴 쪽이 커밋한 뒤에야 지갑
     * 잠금을 얻어 이미 줄어든 잔액에서 다시 debit 하지만, 곧바로 유니크 위반으로 그 debit 까지 함께
     * 롤백된다. 그래서 "잔액이 두 번 줄어드는" 창은 커밋되지 않는다.
     *
     * @throws BusinessException 지갑 없음 (→ 500), 잔액 부족 (→ 409)
     */
    @Transactional
    public WithdrawalResponse save(Long riderId, WithdrawalRequest request) {
        Optional<RiderWithdrawal> alreadyRequested = riderWithdrawalRepository
                .findByRider_MemberIdAndRequestKey(riderId, request.requestKey());
        if (alreadyRequested.isPresent()) {
            return WithdrawalResponse.from(alreadyRequested.get());
        }

        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "포인트 지갑을 찾을 수 없습니다. riderId=" + riderId));

        long balanceBefore = wallet.getBalance();
        if (balanceBefore < request.amount()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "포인트 잔액이 부족합니다. balance=%d, amount=%d"
                            .formatted(balanceBefore, request.amount()));
        }

        wallet.debit(request.amount());
        RiderProfile rider = riderProfileRepository.getReferenceById(riderId);
        RiderWithdrawal withdrawal = RiderWithdrawal.request(rider, request.requestKey(),
                request.amount(), request.bankCode(), maskAccountNumber(request.accountNumber()),
                request.accountHolderName());

        // 지연 flush 로는 유니크 위반이 커밋 시점에 터져 호출자의 catch 를 지나쳐 버린다.
        riderWithdrawalRepository.saveAndFlush(withdrawal);

        pointTransactionRepository.save(PointTransaction.forWithdrawal(
                wallet, PointTransactionType.WITHDRAWAL, request.amount(), balanceBefore,
                request.requestKey(), withdrawal));

        return WithdrawalResponse.from(withdrawal);
    }

    /** 동시 재전송에서 진 요청의 복구 조회. {@link #save} 가 롤백된 <b>뒤에</b> 불러야 한다. */
    @Transactional(readOnly = true)
    public Optional<WithdrawalResponse> findByRequestKey(Long riderId, String requestKey) {
        return riderWithdrawalRepository.findByRider_MemberIdAndRequestKey(riderId, requestKey)
                .map(WithdrawalResponse::from);
    }

    /** 뒤 4자리만 남기고 나머지는 마스킹한다. 원본은 이 메서드 호출 이후 유지하지 않는다. */
    private String maskAccountNumber(String accountNumber) {
        int visibleLength = Math.min(4, accountNumber.length());
        String visible = accountNumber.substring(accountNumber.length() - visibleLength);
        return "*".repeat(accountNumber.length() - visibleLength) + visible;
    }
}
