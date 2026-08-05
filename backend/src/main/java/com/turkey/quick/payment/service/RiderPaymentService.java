package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderPayoutAccount;
import com.turkey.quick.rider.domain.RiderWithdrawal;
import com.turkey.quick.rider.repository.RiderPayoutAccountRepository;
import com.turkey.quick.rider.repository.RiderWithdrawalRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 포인트·정산 서비스(RIDE-POINT-002, #67).
 *
 * <p><b>고객과 로직이 같은데 왜 클래스를 나누는가</b>: 지갑 조회 자체는 {@code CustomerPaymentService}
 * 와 두 줄이 같다. 그럼에도 액터별로 나눈 이유는 세션·인터셉터를 고객/라이더가 각자 패키지에
 * 중복해 두기로 한 판단(`CLAUDE.md` 「확인이 필요한 항목」)과 같다 — 아직 존재하지 않는 공통점을
 * 미리 추상화하지 않는다. 게다가 라이더 쪽에는 곧 정산 내역·거래 내역·출금(잔액 선차감 + 원장 기록)이
 * 붙고 그것들은 고객과 전혀 겹치지 않으므로, 지금 공용 클래스를 만들면 곧 다시 갈라야 한다.
 *
 * <p><b>출금 중복 반영을 막는 방법</b>(이슈 비고): 이 조회는 {@code point_wallet.balance} 를 그대로
 * 돌려줄 뿐 진행 중 출금을 빼거나 더하지 않는다. 출금은 요청 시점에 잔액을 <b>선차감</b>하고
 * WITHDRAWAL 원장을 남기며, 송금이 실패하면 WITHDRAWAL_REFUND 로 되돌린다({@code RiderPointApi}).
 * 즉 PENDING 출금은 이미 잔액에서 빠져 있고 실패 출금은 이미 복구되어 있다 — 여기서 다시 보정하면
 * 그게 곧 이중 반영이다. 잔액 계산을 원장 합산으로 다시 하지 않는 이유도 같다.
 */
@Service
@RequiredArgsConstructor
public class RiderPaymentService {

    /**
     * 출금 최소 금액(사람 확인, #68). 이보다 적은 금액은 출금 요청 자체를 만들지 않는다.
     * 충전 최소 단위(#32, 1,000원)와는 별개 정책이라 값을 공유하지 않는다 — 출금은 은행 이체
     * 건당 비용이 실제로 발생할 수 있어 더 큰 하한을 둔다.
     */
    private static final long MIN_WITHDRAWAL_AMOUNT = 5_000L;

    private final PointWalletRepository pointWalletRepository;
    private final RiderPayoutAccountRepository riderPayoutAccountRepository;
    private final RiderWithdrawalRepository riderWithdrawalRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /**
     * 출금 가능 포인트 잔액 조회(이슈 처리 흐름 ②③).
     *
     * <p>세션 인터셉터가 이미 쿠키 → 세션 → 회원 재조회 → 역할·상태 확인을 마쳤으므로 여기서 회원을
     * 다시 조회하지 않는다. 미로그인·만료·역할 불일치는 이 메서드에 도달하기 전에 401 로 끝난다.
     *
     * <p>지갑은 회원가입 트랜잭션에서 잔액 0 으로 함께 생성된다({@code RiderSignupService}).
     * 따라서 "지갑 없음"은 사용자가 고칠 수 있는 요청 오류가 아니라 서버 데이터 정합성 오류이고,
     * 그래서 4xx 가 아니라 500 으로 낸다({@code RiderPointApi} 문서와 일치).
     * 고객 쪽({@code CustomerPaymentService})은 같은 상황을 {@code IllegalStateException} → 400 으로
     * 내고 있어 지금 두 API 의 동작이 다르다 — 이번 이슈 범위 밖이라 건드리지 않고 미결로 남긴다.
     *
     * @param riderId 세션에서 확인된 라이더 식별자. 요청 파라미터·바디로 받지 않는다.
     * @throws BusinessException 지갑이 없음 (→ 500)
     */
    @Transactional(readOnly = true)
    public PointBalanceResponse getPointBalance(Long riderId) {
        PointWallet wallet = pointWalletRepository.findByMemberId(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "포인트 지갑을 찾을 수 없습니다. riderId=" + riderId));

        return new PointBalanceResponse(wallet.getBalance(), wallet.getUpdatedAt());
    }

    /**
     * 출금 요청(RIDE-POINT-003, #68). 선차감 모델이다 — 요청 즉시 잔액을 줄이고 WITHDRAWAL 원장을
     * 남긴다. 결과는 항상 PENDING 이다. 모의 성공·실패 처리와 실패 시 포인트 복구는 별도 이슈
     * (#90, RIDE-POINT-006)가 담당하므로 여기서는 {@link RiderWithdrawal#complete()}·
     * {@link RiderWithdrawal#fail(String)} 를 호출하지 않는다.
     *
     * <p><b>출금 계좌 미등록</b>도 <b>잔액 부족</b>과 같은 409 로 응답한다({@code RiderPointApi}
     * 문서에서 이미 확정) — 이 저장소에서 409 는 그 자체로 "지금 이 상태로는 처리할 수 없다"는
     * 뜻이라 사유를 코드로 더 세분화하지 않았다. 계좌 등록 API 는 아직 없다(#87, Backlog) — 그
     * 전까지는 이 경로가 항상 409 로 끝나지만, 도메인·리포지토리는 등록 여부와 무관하게 옳다.
     *
     * <p><b>잠금은 지갑 한 곳뿐이다.</b> 이 트랜잭션은 point_charge 를 건드리지 않으므로
     * {@code point_charge → point_wallet} 잠금 순서 규칙과 무관하다.
     *
     * <p><b>멱등성</b>: 순차 재전송은 {@code (rider_id, request_key)} 로 기존 요청을 찾아 그대로
     * 돌려준다. 동시 재전송은 두 트랜잭션이 모두 조회에서 기존 요청을 못 찾고 진행하다가,
     * {@code uk_rider_withdrawal_request} 위반으로 늦은 쪽이 걸린다 — {@code saveAndFlush} 로 즉시
     * 플러시해야 이 시점에 예외를 잡을 수 있다({@code CustomerPaymentService#chargePointRequest} 와
     * 같은 이유). 잡은 뒤에는 그대로 던져 트랜잭션을 롤백시킨다 — 그래야 방금 debit 한 잔액도 함께
     * 되돌아간다.
     *
     * @param riderId 세션에서 확인된 라이더 식별자
     * @param request 출금 요청(멱등키·금액)
     * @throws IllegalArgumentException 최소 출금 금액 미달 (→ 400)
     * @throws BusinessException        계좌 미등록 또는 잔액 부족 (→ 409), 동시 재전송 (→ 409)
     */
    @Transactional
    public WithdrawalResponse requestWithdrawal(Long riderId, WithdrawalRequest request) {
        if (request.amount() < MIN_WITHDRAWAL_AMOUNT) {
            throw new IllegalArgumentException(
                    "출금 금액은 %,d포인트 이상이어야 합니다. amount=%d"
                            .formatted(MIN_WITHDRAWAL_AMOUNT, request.amount()));
        }

        Optional<RiderWithdrawal> alreadyRequested = riderWithdrawalRepository
                .findByRider_MemberIdAndRequestKey(riderId, request.requestKey());
        if (alreadyRequested.isPresent()) {
            return toResponse(alreadyRequested.get());
        }

        RiderPayoutAccount account = riderPayoutAccountRepository.findByRiderId(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "등록된 출금 계좌가 없습니다. 계좌를 먼저 등록해 주세요."));

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
        RiderWithdrawal withdrawal =
                RiderWithdrawal.request(account, request.requestKey(), request.amount());

        try {
            riderWithdrawalRepository.saveAndFlush(withdrawal);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 처리 중인 출금 요청입니다. 잠시 후 다시 시도해 주세요.");
        }

        pointTransactionRepository.save(PointTransaction.forWithdrawal(
                wallet, PointTransactionType.WITHDRAWAL, request.amount(), balanceBefore,
                request.requestKey(), withdrawal));

        return toResponse(withdrawal);
    }

    private WithdrawalResponse toResponse(RiderWithdrawal withdrawal) {
        return new WithdrawalResponse(
                withdrawal.getId(),
                withdrawal.getStatus(),
                withdrawal.getAmount(),
                withdrawal.getBankCodeSnapshot(),
                withdrawal.getMaskedAccountNumberSnapshot(),
                withdrawal.getAccountHolderNameSnapshot(),
                withdrawal.getFailureReason(),
                withdrawal.getRequestedAt(),
                withdrawal.getProcessedAt());
    }
}
