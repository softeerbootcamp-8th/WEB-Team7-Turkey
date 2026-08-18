package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.dto.PointTransactionResponse;
import com.turkey.quick.payment.dto.WithdrawalProcessRequest;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.domain.RiderWithdrawal;
import com.turkey.quick.rider.domain.WithdrawalStatus;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.rider.repository.RiderWithdrawalRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private static final Logger log = LoggerFactory.getLogger(RiderPaymentService.class);

    /**
     * 출금 최소 금액(사람 확인, #68). 이보다 적은 금액은 출금 요청 자체를 만들지 않는다.
     * 충전 최소 단위(#32, 1,000원)와는 별개 정책이라 값을 공유하지 않는다 — 출금은 은행 이체
     * 건당 비용이 실제로 발생할 수 있어 더 큰 하한을 둔다.
     */
    private static final long MIN_WITHDRAWAL_AMOUNT = 5_000L;

    private final PointWalletRepository pointWalletRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final RiderWithdrawalRepository riderWithdrawalRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /** 실 은행 API 를 붙이면 이 빈만 갈아끼운다. MVP 는 {@code MockPayoutGateway}. */
    private final PayoutGateway payoutGateway;

    private final WithdrawalProcessor withdrawalProcessor;

    /** 출금 신청의 DB 트랜잭션 구간. 별도 빈이라야 {@code @Transactional} 프록시를 탄다. */
    private final WithdrawalRequester withdrawalRequester;

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
     * 정산·출금 내역 조회(RIDE-POINT-004, #69).
     *
     * <p>별도 조회를 만들지 않고 {@code point_transaction} 원장을 그대로 노출한다 — 정산 적립
     * (SETTLEMENT)과 출금 선차감·복구(WITHDRAWAL·WITHDRAWAL_REFUND)가 이미 그 원장에 한 줄씩
     * 남기 때문이다({@code PointTransaction.forSettlement}·{@code forWithdrawal}). 별도의
     * "정산 내역"·"출금 내역" 조회(이 컨트롤러의 {@code getSettlements}·{@code getWithdrawals})는
     * 각자 다른 화면(운행 기록 주간 합계, 출금 요청 상세)의 몫이라 이 이슈에서 함께 구현하지 않는다.
     *
     * <p>상단 카드에 쓰는 {@code balance}는 목록에 나온 마지막 거래의 {@code balanceAfter}가 아니라
     * 지갑의 <b>현재</b> 잔액이다 — type 필터가 걸리거나 페이지가 뒤로 갈수록 둘은 달라진다.
     *
     * <p>페이지 정보가 잘못되면(음수 page, 0 이하 size) {@link PageRequest#of}가 던지는
     * {@code IllegalArgumentException}을 그대로 흘려보낸다 — {@code GlobalExceptionHandler}가
     * 이미 400으로 바꾸므로 여기서 별도로 검증하지 않는다({@code DeliveryListQueryService}와 같은 패턴).
     *
     * @param riderId 세션에서 확인된 라이더 식별자
     * @param type    거래 유형 필터. null이면 전체
     * @throws BusinessException       지갑이 없음 (→ 500, {@link #getPointBalance}와 같은 판단)
     * @throws IllegalArgumentException 잘못된 페이지 정보 (→ 400)
     */
    @Transactional(readOnly = true)
    public PointTransactionListResponse getPointTransactions(Long riderId, PointTransactionType type,
                                                              int page, int size) {
        PointWallet wallet = pointWalletRepository.findByMemberId(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "포인트 지갑을 찾을 수 없습니다. riderId=" + riderId));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PointTransaction> result = type == null
                ? pointTransactionRepository.findByWallet_MemberId(riderId, pageable)
                : pointTransactionRepository.findByWallet_MemberIdAndTransactionType(riderId, type, pageable);

        List<PointTransactionResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PointTransactionListResponse(
                wallet.getBalance(), items, page, size, result.getTotalElements());
    }

    /**
     * 소스 FK는 유형별로 정확히 하나만 채워진다(ck_point_transaction_source). 나머지는 lazy 프록시라도
     * null이면 그대로 null이고, non-null이면 식별자 접근만으로는 추가 조회가 일어나지 않는다.
     *
     * <p>WITHDRAWAL·WITHDRAWAL_REFUND 행은 {@code withdrawalStatus}도 함께 채운다(#90 후속) — 화면이
     * "포인트 출금"이라는 라벨만으로는 대기 중인지 완료됐는지 구분하지 못했던 문제를 고치기 위해서다.
     * {@code getRiderWithdrawal()}에 접근해도 추가 쿼리가 나가지 않는다 — 호출자
     * ({@link #getPointTransactions})가 이미 fetch join 으로 함께 읽어 왔다
     * ({@code PointTransactionRepository} 주석 참조).
     */
    private PointTransactionResponse toResponse(PointTransaction transaction) {
        RiderWithdrawal withdrawal = transaction.getRiderWithdrawal();
        return new PointTransactionResponse(
                transaction.getId(),
                transaction.getTransactionType(),
                transaction.getDirection(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getDeliveryOrder() != null ? transaction.getDeliveryOrder().getId() : null,
                transaction.getPointCharge() != null ? transaction.getPointCharge().getId() : null,
                transaction.getRiderSettlement() != null ? transaction.getRiderSettlement().getId() : null,
                withdrawal != null ? withdrawal.getId() : null,
                withdrawal != null ? withdrawal.getStatus() : null,
                transaction.getCreatedAt());
    }

    /**
     * 출금 요청(RIDE-POINT-003, #68). 선차감 모델이다 — 요청 즉시 잔액을 줄이고 WITHDRAWAL 원장을
     * 남긴다. 결과는 항상 PENDING 이다. 모의 성공·실패 처리와 실패 시 포인트 복구는 별도 이슈
     * (#90, RIDE-POINT-006)가 담당하므로 여기서는 {@link RiderWithdrawal#complete()}·
     * {@link RiderWithdrawal#fail(String)} 를 호출하지 않는다.
     *
     * <p><b>계좌 정보는 요청 바디로 받는다</b>(사람 확인, 2026-08-11). 사전 등록 계좌
     * ({@code rider_payout_account}) 방식 대신 신청 시점 입력으로 계약을 바꿨다 — #87(계좌 등록
     * API)이 아직 없어 등록 계좌 방식으로는 이 경로가 항상 409 로 막혀 있었다. 원본 계좌번호는
     * 마스킹 직후 버리고 지역 변수 밖으로 내보내지 않는다 — {@link RiderWithdrawal}에도 마스킹된
     * 값만 넘긴다.
     *
     * <p><b>잠금은 지갑 한 곳뿐이다.</b> 이 트랜잭션은 point_charge 를 건드리지 않으므로
     * {@code point_charge → point_wallet} 잠금 순서 규칙과 무관하다.
     *
     * <p><b>멱등성</b>: 같은 {@code requestKey} 는 순차든 동시든 같은 출금 건을 돌려주고, 잔액은 한
     * 번만 줄어든다. 순차 재전송은 {@link WithdrawalRequester#save} 의 선조회가 흡수한다. 동시
     * 재전송은 두 트랜잭션이 모두 조회에서 기존 요청을 못 찾고 진행하다가
     * {@code uk_rider_withdrawal_request} 위반으로 늦은 쪽이 걸리는데, 그 예외를 여기서 받아
     * <b>롤백이 끝난 뒤</b> 새 트랜잭션으로 이긴 건을 재조회해 돌려준다. 진 요청이 방금 debit 한
     * 잔액은 그 롤백과 함께 되돌아가 있다.
     *
     * <p><b>이 메서드에는 {@code @Transactional} 이 없다.</b> 트랜잭션 구간은
     * {@code WithdrawalRequester} 가 연다({@code PointChargeApprover} 와 같은 이유로 별도 빈이다).
     * 여기에 붙이면 재조회가 rollback-only 로 표시된 트랜잭션 안에서 일어나 커밋 때 다시 터진다.
     *
     * <p>재조회로도 못 찾은 제약 위반은 멱등키 충돌이 아니므로(이 시점의 rider_withdrawal 은
     * {@code provider_transfer_key} 가 NULL 이라 다른 유니크가 걸릴 수 없다) 409 로 포장하지 않고
     * 원인 예외를 그대로 올린다.
     *
     * @param riderId 세션에서 확인된 라이더 식별자
     * @param request 출금 요청(멱등키·금액·계좌 정보)
     * @throws IllegalArgumentException 최소 출금 금액 미달 (→ 400)
     * @throws BusinessException        잔액 부족 (→ 409)
     */
    public WithdrawalResponse requestWithdrawal(Long riderId, WithdrawalRequest request) {
        if (request.amount() < MIN_WITHDRAWAL_AMOUNT) {
            throw new IllegalArgumentException(
                    "출금 금액은 %,d포인트 이상이어야 합니다. amount=%d"
                            .formatted(MIN_WITHDRAWAL_AMOUNT, request.amount()));
        }

        try {
            return withdrawalRequester.save(riderId, request);
        } catch (DataIntegrityViolationException e) {
            // 여기 도달했다 = 위 트랜잭션이 롤백을 끝냈다(선차감도 되돌아갔다).
            return withdrawalRequester.findByRequestKey(riderId, request.requestKey())
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 출금 모의 처리(RIDE-POINT-006, #90). {@code CustomerPaymentService#confirmPointCharge} 와
     * 같은 구조다 — 실제 은행 이체 API 를 부르는 것처럼 {@link PayoutGateway} 를 거쳐 결과를 받고,
     * 그 결과로 PENDING → COMPLETED/FAILED 를 확정한다. 이 메서드에는 {@code @Transactional} 이
     * 없다: 트랜잭션 안에서 외부 호출을 하면 응답을 기다리는 동안 커넥션과 행 잠금을 함께 쥔다.
     *
     * <p>흐름은 세 구간이다.
     * <ol>
     *   <li><b>사전 검증</b>(잠금 없는 읽기) — 소유·상태. 명백한 오류를 이체 호출 전에 걸러낸다.
     *       존재하지 않는 요청과 타인의 요청은 같은 404 로 응답한다({@code RiderDeliveryHistoryService},
     *       #71 과 같은 판단).
     *   <li><b>이체 실행</b>(트랜잭션 밖) — 성공·실패는 {@link PayoutGateway} 가 결정한다. 호출자가
     *       "성공/실패"를 직접 통보하지 않는다({@code MockPayoutGateway} 주석 참조).
     *   <li><b>이체 식별자 선커밋</b> — {@link WithdrawalProcessor#recordTransferReceived}(V19).
     *       뒤가 실패해도 "이체는 됐다"는 사실이 DB 에 남는다({@code PointChargeApprover} 와 동일).
     *   <li><b>확정</b> — {@link WithdrawalProcessor#complete}·{@link WithdrawalProcessor#fail} 이
     *       상태·(실패 시)잔액·원장을 한 트랜잭션에 반영한다. 여기서 잠금을 다시 잡고 PENDING 을
     *       재확인하므로, 중복 처리 요청이 동시에 들어와도 하나만 반영된다(처리 흐름 ②③).
     * </ol>
     *
     * <p><b>TIMEOUT 은 확정하지 않는다.</b> 이체가 성사됐는지 알 수 없는 상태에서 FAILED 로 확정하면
     * 실제로는 이체된 건의 포인트를 다시 내주는 이중 지급이 될 수 있다({@code confirmPointCharge}
     * 와 같은 판단) — PENDING 을 유지하고 502 로 응답한다.
     *
     * @param riderId      세션에서 확인된 라이더 식별자
     * @param withdrawalId 처리할 출금 식별자
     * @param request      모의 이체 결과를 통제하는 토큰
     * @throws BusinessException 요청 없음/타인 것 (→ 404), 이미 처리됨 (→ 409), 이체 결과 불명 (→ 502)
     */
    public WithdrawalResponse processWithdrawal(Long riderId, Long withdrawalId,
                                                 WithdrawalProcessRequest request) {
        RiderWithdrawal withdrawal = riderWithdrawalRepository
                .findByIdAndRider_MemberId(withdrawalId, riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 출금 요청입니다."));
        if (withdrawal.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 처리된 출금 요청입니다. status=" + withdrawal.getStatus());
        }

        PayoutGateway.Transfer transfer;
        try {
            transfer = payoutGateway.transfer(new PayoutGateway.TransferCommand(
                    withdrawal.getRequestKey(),
                    withdrawal.getBankCodeSnapshot(),
                    withdrawal.getMaskedAccountNumberSnapshot(),
                    withdrawal.getAccountHolderNameSnapshot(),
                    withdrawal.getAmount(),
                    request.authToken()));
        } catch (PayoutGateway.PayoutGatewayException e) {
            if (e.getFailureType() == PayoutGateway.PayoutGatewayException.FailureType.TIMEOUT) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "송금 결과를 확인할 수 없습니다. 잠시 후 출금 내역을 확인해 주세요.");
            }
            return toResponse(
                    withdrawalProcessor.fail(withdrawalId, riderId, failureReasonOf(e)));
        }

        log.info("[출금-모의처리] 이체 성공 withdrawalId={}, providerTransferKey={}",
                withdrawalId, transfer.providerTransferKey());

        // 뒤가 실패해도 이체 사실이 남도록 식별자만 먼저 커밋한다.
        withdrawalProcessor.recordTransferReceived(withdrawalId, riderId, transfer.providerTransferKey());

        return toResponse(withdrawalProcessor.complete(withdrawalId, riderId));
    }

    private String failureReasonOf(PayoutGateway.PayoutGatewayException e) {
        String reason = e.getProviderErrorCode() == null
                ? e.getMessage()
                : e.getProviderErrorCode() + ": " + e.getMessage();
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    private WithdrawalResponse toResponse(RiderWithdrawal withdrawal) {
        return WithdrawalResponse.from(withdrawal);
    }
}
