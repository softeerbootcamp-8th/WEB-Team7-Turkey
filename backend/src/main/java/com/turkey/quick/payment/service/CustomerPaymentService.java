package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class CustomerPaymentService {

    /**
     * 충전 금액 정책. <b>잠정값이다</b>(#32) — 팀 합의 전 임의로 고정한 수치이므로 확정되면 이 세 상수만
     * 바꾼다. 화면(charge.tsx)은 1·3·5·10만원 프리셋만 제공하지만 서버는 그보다 넓게 받아 둔다:
     * 프리셋이 바뀔 때마다 서버를 고치지 않아도 되고, 서버가 화면보다 좁으면 프리셋이 곧바로 깨진다.
     */
    private static final long MIN_CHARGE_AMOUNT = 1_000L;
    private static final long MAX_CHARGE_AMOUNT = 1_000_000L;
    private static final long CHARGE_AMOUNT_UNIT = 1_000L;

    private final PointWalletRepository pointWalletRepository;
    private final PointChargeRepository pointChargeRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final MemberRepository memberRepository;

    /** 실 PG 를 붙이면 이 빈만 갈아끼운다. MVP 는 {@code MockPaymentGateway}. */
    private final PaymentGateway paymentGateway;

    public PointBalanceResponse getPointBalance(Long customerId) {
        PointWallet pointWallet = pointWalletRepository.findByMemberId(customerId)
                .orElseThrow(() -> new IllegalStateException("계좌 정보가 없습니다."));

        return new PointBalanceResponse(pointWallet.getBalance(), pointWallet.getUpdatedAt());
    }

    /**
     * 포인트 충전 준비(CUS-POINT-002, #32).
     *
     * <p>하는 일은 세 가지다: 금액 검증 → 멱등키로 기존 요청 확인 → {@code point_charge} 를 PENDING
     * 으로 저장. <b>잔액은 여기서 변하지 않는다</b> — 지갑 증가와 원장 기록은 승인(confirm)
     * 트랜잭션 몫이다. 그래서 이 메서드는 지갑을 조회하지 않는다. 지갑은 회원가입 때 함께 생성되므로
     * "지갑 없음"은 준비 단계에서 미리 막을 사용자 오류가 아니라 정합성 오류이고, 그 판정은 잔액을
     * 실제로 쓰는 승인 단계에서 하는 편이 맞다.
     *
     * <p>MVP 는 모의 결제라 이 시점에 PG 호출이 없다. 실 PG 를 붙이면 {@link PaymentGateway#prepare}
     * 를 저장 전후에 끼워 넣고 그 결과(provider, 결제창 정보)를 응답에 더하게 된다.
     *
     * <p><b>멱등성</b>: 같은 {@code chargeRequestKey} 로 다시 오면 새로 만들지 않고 기존 건을 그대로
     * 돌려준다(순차 재전송). 반면 <b>동시</b> 재전송은 {@code uk_point_charge_customer_request} 위반을
     * 409 로 바꿔 거부한다 — 여기서 굳이 다시 조회해 기존 건을 돌려주지 않는 이유는, 제약 위반이
     * 발생한 트랜잭션은 이미 rollback-only 로 표시돼 있어 같은 트랜잭션에서 추가 조회를 하면
     * 커밋 시점에 다시 터지기 때문이다. 따닥 클릭은 대개 완전 동시가 아니라 위의 조회 경로로
     * 흡수되고, 진짜 동시 요청은 명확한 실패를 받는 편이 부분 성공보다 낫다.
     *
     * @param request    충전 요청(멱등키·금액·결제수단)
     * @param customerId 세션에서 확인된 고객 식별자. 요청 바디로 받지 않는다.
     * @throws IllegalArgumentException 금액이 허용 범위·단위를 벗어남 (→ 400)
     * @throws BusinessException        같은 멱등키의 충전 요청이 동시에 처리됨 (→ 409)
     */
    @Transactional
    public PointChargeResponse chargePointRequest(PointChargeRequest request, Long customerId) {
        validateChargeAmount(request.amount());


        // 멱등성 보장(클라이언트가 멱등키를 제공함, 클릭마다 키가 생성되지 않게 설정할 필요가 있음.)
        Optional<PointCharge> alreadyRequested = pointChargeRepository
                .findByCustomer_IdAndChargeRequestKey(customerId, request.chargeRequestKey());
        if (alreadyRequested.isPresent()) {
            return toResponse(alreadyRequested.get());
        }

        // 세션 인터셉터가 이미 회원 존재·역할·상태를 확인했으므로 여기서 다시 조회하지 않는다.
        // FK 를 채우는 것이 목적이라 프록시 참조로 충분하고, 실제 존재 여부는 FK 제약이 보증한다.
        Member customer = memberRepository.getReferenceById(customerId);

        PointCharge pointCharge = PointCharge.request(
                customer,
                request.chargeRequestKey(),
                request.paymentMethod(),
                request.amount(),
                MockPaymentGateway.PROVIDER);

        try {
            // save 가 아니라 saveAndFlush 다: 지연 flush 로는 유니크 위반이 커밋 시점에 터져
            // 아래 catch 가 잡지 못하고 500 으로 새어 나간다.
            return toResponse(pointChargeRepository.saveAndFlush(pointCharge));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 처리 중인 충전 요청입니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 금액 검증(이슈 처리흐름 ②). 범위와 단위를 나눠 검사해 사용자가 무엇을 고쳐야 하는지 알 수 있게 한다.
     *
     * <p>{@code @Positive} 가 DTO 에 이미 있어 0 이하는 여기 오기 전에 걸러지지만, 서비스를 직접
     * 호출하는 경로(테스트·내부 호출)에서도 불변식이 성립하도록 하한을 다시 본다.
     */
    private void validateChargeAmount(long amount) {
        if (amount < MIN_CHARGE_AMOUNT || amount > MAX_CHARGE_AMOUNT) {
            throw new IllegalArgumentException(
                    "충전 금액은 %,d원 이상 %,d원 이하여야 합니다. amount=%d"
                            .formatted(MIN_CHARGE_AMOUNT, MAX_CHARGE_AMOUNT, amount));
        }
        if (amount % CHARGE_AMOUNT_UNIT != 0) {
            throw new IllegalArgumentException(
                    "충전 금액은 %,d원 단위여야 합니다. amount=%d"
                            .formatted(CHARGE_AMOUNT_UNIT, amount));
        }
    }

    /**
     * 포인트 충전 모의 승인(CUS-POINT-003, #33).
     *
     * <p>성공 시 <b>한 트랜잭션에서 세 가지를</b> 한다: 충전 PENDING→PAID, 지갑 잔액 증가,
     * CHARGE 원장 1행 기록. 셋 중 하나라도 실패하면 전부 롤백된다(이슈 예외처리).
     *
     * <p><b>잠금 순서</b>: {@code point_charge} → {@code point_wallet}. 둘 다
     * {@code SELECT ... FOR UPDATE} 로 잠근다. 앞의 잠금이 같은 충전 건에 대한 동시 승인을 직렬화하고,
     * 뒤의 잠금이 잔액 read-modify-write 의 lost update 를 막는다. <b>포인트를 만지는 다른 흐름
     * (배송 결제·정산)도 이 순서를 지켜야 한다</b> — 순서가 엇갈리면 서로 상대가 쥔 행을 기다려
     * 데드락이 된다.
     *
     * <p><b>멱등성</b>(이슈 비고): 이미 PAID 인 충전에 다시 승인 요청이 오면 잔액을 또 늘리지 않고
     * 그때의 결과를 그대로 돌려준다. 반환하는 잔액은 지갑의 현재 잔액이 아니라 <b>그 승인 시점의
     * 원장 {@code balance_after}</b> 다 — 이후 다른 거래로 잔액이 바뀌었을 수 있어 현재 잔액은
     * "승인 반영 후 잔액"이 아니다.
     *
     * <p><b>승인 여부는 {@link PaymentGateway} 가 결정한다.</b> MVP 는 {@code MockPaymentGateway} 가
     * 프로세스 안에서 결과를 만들지만 호출 모양은 실 PG 와 같다 — 실 연동은 구현체를 갈아끼우는 것으로
     * 끝나고 이 메서드와 API 계약은 그대로다.
     *
     * <p>게이트웨이가 <b>거절</b>({@code DECLINED}·{@code PROVIDER_ERROR})하면 예외를 밖으로 던지지 않고
     * PENDING→FAILED 로 확정해 정상 응답(status=FAILED)으로 돌려준다. 예외를 던지면 트랜잭션이
     * 롤백되어 FAILED 전이 자체가 사라지기 때문이다. 잔액과 원장은 건드리지 않는다.
     *
     * <p>반면 <b>{@code TIMEOUT}</b> 은 결제 성사 여부를 알 수 없는 상태다. FAILED 로 확정하면 실제로
     * 결제된 건을 실패로 못 박게 되므로 PENDING 을 유지하고 502 로 알린다 — 조회·대조로 나중에 살릴
     * 여지를 남긴다.
     *
     * <p><b>알려진 긴장</b>: 게이트웨이 호출이 트랜잭션과 행 잠금 안에서 일어난다. 모의 PG 는 네트워크
     * 대기가 없어 문제가 없지만, 실 PG 를 붙이면 HTTP 응답을 기다리는 동안 지갑 행 잠금을 쥐게 된다.
     * 그때는 "잠금·검증 → (트랜잭션 밖) PG 호출 → 잠금·확정" 3단계로 쪼개거나 잠금 시간을 감수할지
     * 결정해야 한다(중간 상태가 필요해 {@code PointChargeStatus} 확장이 따라온다).
     *
     * @param pointChargeId 승인 대상 충전 식별자
     * @param request       대조용 금액과 결제창 인증 토큰
     * @param customerId    세션에서 확인된 고객 식별자
     * @throws BusinessException 404 없음 / 403 본인 아님 / 400 금액 불일치 / 409 승인 불가 상태 /
     *                           502 PG 응답 불명(PENDING 유지)
     */
    @Transactional
    public PointChargeConfirmResponse confirmPointCharge(Long pointChargeId,
                                                         PointChargeConfirmRequest request,
                                                         Long customerId) {
        // ① 충전 요청 확인 — 잠금 순서상 지갑보다 먼저 잠근다.
        PointCharge pointCharge = pointChargeRepository.findByIdForUpdate(pointChargeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 충전 요청입니다."));

        // 소유 확인. customer 는 LAZY 프록시지만 식별자 접근은 조회를 유발하지 않는다.
        if (!pointCharge.getCustomer().getId().equals(customerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 충전 요청이 아닙니다.");
        }

        // ④ 금액 일치 확인. 전달값은 대조에만 쓰고 승인 금액은 항상 DB 의 requested_amount 다.
        if (request.amount() != pointCharge.getRequestedAmount()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "요청 금액과 결제 금액이 일치하지 않습니다. requested=%d, paid=%d"
                            .formatted(pointCharge.getRequestedAmount(), request.amount()));
        }

        // ⑤ 중복 승인 확인 — 이미 승인된 건이면 잔액을 다시 늘리지 않고 그때 결과를 돌려준다.
        if (pointCharge.getStatus() == PointChargeStatus.PAID) {
            return alreadyApprovedResponse(pointCharge);
        }

        // ② 상태 확인. FAILED·CANCELED·REFUNDED 는 승인 대상이 아니다.
        if (pointCharge.getStatus() != PointChargeStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "승인할 수 없는 상태입니다. status=" + pointCharge.getStatus());
        }

        // ③ 승인 결과 검증 — PG 에 승인을 요청하고 그 판단을 따른다.
        PaymentGateway.Approval approval;
        try {
            approval = paymentGateway.confirm(new PaymentGateway.ConfirmCommand(
                    pointCharge.getChargeRequestKey(),
                    // 준비 단계 식별자를 저장할 컬럼이 아직 없다(토스류는 불필요, 카카오류는 필요).
                    null,
                    request.authToken(),
                    pointCharge.getRequestedAmount()));
        } catch (PaymentGateway.PaymentGatewayException e) {
            return handleGatewayFailure(pointCharge, customerId, e);
        }

        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));

        // 원장의 balance_before 는 이 변경 직전의 확정 잔액이어야 한다. 잠금 상태에서 읽었으므로
        // credit 이전 값을 그대로 쓸 수 있다.
        long balanceBefore = wallet.getBalance();

        // ⑧ 충전 요청 상태를 완료로 변경. 승인 식별자·카드사 정보는 PG 응답에서 온다.
        pointCharge.approve(
                approval.providerPaymentKey(),
                approval.issuerCode(),
                approval.maskedPaymentMethod());
        // ⑥ 포인트 잔액 증가 — 승인 금액은 요청 금액 전액이다.
        wallet.credit(pointCharge.getApprovedAmount());
        // ⑦ 충전 내역 저장
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
     * 이미 승인된 충전의 결과를 재구성한다. 잔액은 승인 당시 원장의 {@code balance_after} 를 쓴다.
     *
     * <p>원장이 없다면 "PAID 인데 잔액이 늘지 않았다"는 뜻이므로 정합성 오류로 다룬다 —
     * 두 변경이 같은 트랜잭션에 있으니 정상 경로에서는 발생할 수 없다.
     */
    private PointChargeConfirmResponse alreadyApprovedResponse(PointCharge pointCharge) {
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

    /**
     * PG 승인 실패 처리. 실패의 성격에 따라 충전 건을 확정할지 PENDING 으로 남길지 갈린다.
     *
     * <p>거절은 결과가 확정된 실패이므로 FAILED 로 못 박고 정상 응답으로 돌려준다(예외를 던지면
     * 트랜잭션이 롤백되어 그 전이가 사라진다). 반면 응답을 받지 못한 경우는 결제 성사 여부를 알 수
     * 없으므로 상태를 건드리지 않고 예외로 알린다 — FAILED 로 확정하면 실제로 결제된 건을 실패로
     * 못 박는 셈이 된다.
     */
    private PointChargeConfirmResponse handleGatewayFailure(
            PointCharge pointCharge, Long customerId, PaymentGateway.PaymentGatewayException e) {

        if (e.getFailureType() == PaymentGateway.PaymentGatewayException.FailureType.TIMEOUT) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "결제 결과를 확인할 수 없습니다. 잠시 후 결제 내역을 확인해 주세요.");
        }

        pointCharge.fail(failureReasonOf(e));
        return new PointChargeConfirmResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                0L,
                currentBalance(customerId),
                null);
    }

    /** {@code point_charge.failure_reason} 은 255자다. PG 오류 코드를 앞에 붙여 원인을 남긴다. */
    private String failureReasonOf(PaymentGateway.PaymentGatewayException e) {
        String reason = e.getProviderErrorCode() == null
                ? e.getMessage()
                : e.getProviderErrorCode() + ": " + e.getMessage();
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    /** 실패 응답에 표시할 잔액. 변경이 없으므로 잠그지 않고 읽는다. */
    private long currentBalance(Long customerId) {
        return pointWalletRepository.findByMemberId(customerId)
                .map(PointWallet::getBalance)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));
    }

    private PointChargeResponse toResponse(PointCharge pointCharge) {
        return new PointChargeResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getRequestedAmount(),
                pointCharge.getPaymentMethod(),
                pointCharge.getRequestedAt());
    }
}
