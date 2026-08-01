package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class CustomerPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPaymentService.class);

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
    private final MemberRepository memberRepository;

    /** 실 PG 를 붙이면 이 빈만 갈아끼운다. MVP 는 {@code MockPaymentGateway}. */
    private final PaymentGateway paymentGateway;

    /** 승인의 DB 트랜잭션 구간. 별도 빈이라야 {@code @Transactional} 프록시를 탄다. */
    private final PointChargeApprover pointChargeApprover;

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
     * <p><b>이 메서드에는 {@code @Transactional} 이 없다.</b> 파사드 역할만 하고, DB 트랜잭션은
     * {@link PointChargeApprover} 가 구간별로 연다. PG 호출을 트랜잭션 안에서 하면 응답을 기다리는
     * 동안 HikariCP 커넥션과 행 잠금을 함께 쥐게 되어, 트래픽이 몰릴 때 커넥션 풀이 마르고
     * 요청이 줄줄이 대기한다.
     *
     * <p>흐름은 네 구간이다.
     *
     * <ol>
     *   <li><b>검증</b>(잠금 없는 읽기) — 소유·금액·상태. 명백한 오류를 돈이 움직이기 전에 걸러낸다.
     *       지갑 존재도 여기서 본다 — 승인 뒤에 발견하면 이미 늦다.
     *   <li><b>PG 승인</b>(트랜잭션 밖) — 승인 여부는 {@link PaymentGateway} 가 결정한다.
     *   <li><b>승인 식별자 선커밋</b> — {@link PointChargeApprover#recordApprovalReceived}.
     *       뒤가 실패해도 "PG 승인은 받았다"는 사실이 DB 에 남는다.
     *   <li><b>확정</b> — {@link PointChargeApprover#finalizeApproval} 이 상태·잔액·원장을 한 트랜잭션에.
     * </ol>
     *
     * <p><b>포기한 것</b>: 확정 구간만 잠그므로 동시 요청이 PG 를 각각 부를 수 있다. 실 PG 는 같은
     * 결제에 대한 재승인을 거절하고, 우리 쪽은 4구간의 상태 재확인이 이중 증액을 막는다. 즉
     * "PG 호출도 한 번"은 포기하고 "포인트 증가는 한 번"만 지킨다.
     *
     * <p><b>남은 위험과 대책</b>: 3구간 커밋 후 4구간이 실패하면 "PG 승인은 받았는데 포인트는 없는" 건이
     * 된다. 그 건은 {@code status = PENDING AND provider_payment_key IS NOT NULL} 로 찾을 수 있고,
     * 실 PG 연동 시 망 취소·조회 대사·웹훅이 이 조건을 입력으로 쓴다(현재 미구현).
     *
     * <p><b>멱등성</b>(이슈 비고): 이미 PAID 인 충전에 다시 승인 요청이 오면 잔액을 또 늘리지 않고
     * 그때의 결과를 돌려준다. 반환 잔액은 지갑의 현재 잔액이 아니라 승인 시점 원장의
     * {@code balance_after} 다.
     *
     * @param pointChargeId 승인 대상 충전 식별자
     * @param request       대조용 금액과 결제창 인증 토큰
     * @param customerId    세션에서 확인된 고객 식별자
     * @throws BusinessException 404 없음 / 403 본인 아님 / 400 금액 불일치 /
     *                           409 승인 불가 상태·결제 확인 중 / 502 PG 응답 불명(PENDING 유지)
     */
    public PointChargeConfirmResponse confirmPointCharge(Long pointChargeId,
                                                         PointChargeConfirmRequest request,
                                                         Long customerId) {
        PointCharge pointCharge = verifyConfirmable(pointChargeId, request, customerId);
        if (pointCharge.getStatus() == PointChargeStatus.PAID) {
            return pointChargeApprover.alreadyApprovedResponse(pointCharge);
        }

        PaymentGateway.Approval approval;
        try {
            approval = paymentGateway.confirm(new PaymentGateway.ConfirmCommand(
                    pointCharge.getChargeRequestKey(),
                    // 준비 단계 식별자를 저장할 컬럼이 아직 없다(토스류는 불필요, 카카오류는 필요).
                    null,
                    request.authToken(),
                    pointCharge.getRequestedAmount()));
        } catch (PaymentGateway.PaymentGatewayException e) {
            if (e.getFailureType() == PaymentGateway.PaymentGatewayException.FailureType.TIMEOUT) {
                // 결제 성사 여부를 알 수 없다. FAILED 로 확정하면 실제로 결제된 건을 실패로 못 박는다.
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "결제 결과를 확인할 수 없습니다. 잠시 후 결제 내역을 확인해 주세요.");
            }
            return pointChargeApprover.markFailed(pointChargeId, customerId, failureReasonOf(e));
        }

        // 뒤가 실패해도 승인 사실이 남도록 식별자만 먼저 커밋한다.
        pointChargeApprover.recordApprovalReceived(pointChargeId, approval.providerPaymentKey());

        try {
            return pointChargeApprover.finalizeApproval(pointChargeId, customerId, approval);
        } catch (RuntimeException e) {
            // 로그 스택이 없어 컬럼(provider_payment_key)이 1차 근거이고, 이 로그는 원인 추적용 보조다.
            log.error("[결제-불일치] PG 승인 후 반영 실패. 수동 대사 필요. "
                            + "pointChargeId={}, chargeRequestKey={}, providerPaymentKey={}, amount={}",
                    pointChargeId, pointCharge.getChargeRequestKey(),
                    approval.providerPaymentKey(), approval.approvedAmount(), e);
            throw e;
        }
    }

    /**
     * 승인 전 사전 검증(이슈 처리흐름 ①②④). 잠금 없이 읽는다 — 확정 구간이 잠그고 다시 확인하므로
     * 여기서 잠글 이유가 없고, 잠그면 PG 호출이 잠금 안으로 들어온다.
     */
    private PointCharge verifyConfirmable(Long pointChargeId, PointChargeConfirmRequest request,
                                          Long customerId) {
        PointCharge pointCharge = pointChargeRepository.findById(pointChargeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 충전 요청입니다."));

        // 소유 확인. customer 는 LAZY 프록시지만 식별자 접근은 조회를 유발하지 않는다.
        if (!pointCharge.getCustomer().getId().equals(customerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 충전 요청이 아닙니다.");
        }

        // 금액 대조. 전달값은 검증에만 쓰고 승인 금액은 항상 DB 의 requested_amount 다.
        if (request.amount() != pointCharge.getRequestedAmount()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "요청 금액과 결제 금액이 일치하지 않습니다. requested=%d, paid=%d"
                            .formatted(pointCharge.getRequestedAmount(), request.amount()));
        }

        if (pointCharge.getStatus() == PointChargeStatus.PAID) {
            return pointCharge;
        }
        if (pointCharge.getStatus() != PointChargeStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "승인할 수 없는 상태입니다. status=" + pointCharge.getStatus());
        }

        // PENDING 인데 승인 식별자가 있다 = 앞선 승인이 반영 도중 끊긴 건이다. 여기서 PG 를 다시 부르면
        // 새 식별자가 기존 값을 덮어써 추적 근거가 사라진다. 대사로만 풀어야 한다.
        if (pointCharge.getProviderPaymentKey() != null) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "결제 확인 중인 충전 요청입니다. 잠시 후 결제 내역을 확인해 주세요.");
        }

        // 지갑 존재를 승인 전에 본다. 승인 뒤에 없는 걸 알면 이미 돈이 나간 뒤다.
        if (!pointWalletRepository.existsById(customerId)) {
            throw new IllegalStateException("지갑 정보가 없습니다. memberId=" + customerId);
        }

        return pointCharge;
    }

    /** {@code point_charge.failure_reason} 은 255자다. PG 오류 코드를 앞에 붙여 원인을 남긴다. */
    private String failureReasonOf(PaymentGateway.PaymentGatewayException e) {
        String reason = e.getProviderErrorCode() == null
                ? e.getMessage()
                : e.getProviderErrorCode() + ": " + e.getMessage();
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
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
