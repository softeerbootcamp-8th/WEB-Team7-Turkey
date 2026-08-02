package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointChargeStatus;
import com.turkey.quick.payment.domain.PointTransaction;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeCancelResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointTransactionRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    /**
     * 승인 전 취소 사유(#34). 클라이언트가 보내지 않고 서버가 채운다 —
     * 근거는 {@link #cancelPointCharge} 주석. {@code failure_reason} 은 255자다.
     */
    private static final String CUSTOMER_CANCEL_REASON = "고객이 결제를 취소했습니다.";

    private final PointWalletRepository pointWalletRepository;
    private final PointChargeRepository pointChargeRepository;
    private final MemberRepository memberRepository;

    /** 주문 결제(#40)의 원장 기록용. 충전 원장은 {@link PointChargeApprover} 가 따로 쓴다. */
    private final PointTransactionRepository pointTransactionRepository;

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
     * 배송요금 포인트 차감(CUS-PAY-002, #40).
     *
     * <p><b>이 서비스가 order 를 주입받지는 않는다.</b> 인자로 {@link DeliveryOrder} 엔터티를 받을
     * 뿐이고 order 쪽 <b>서비스</b>는 참조하지 않는다 — 반대로 엮으면
     * {@code DeliveryService → CustomerPaymentService} 와 맞물려 스프링 빈 순환이 되고, Boot 3.4 는
     * 순환 참조가 기본 금지라 컴파일이 아니라 <b>기동</b>이 실패한다. 엔터티 참조는
     * {@code PointTransaction.deliveryOrder} 가 이미 하고 있어 새로 생기는 방향이 아니다.
     *
     * <p><b>{@code Propagation.MANDATORY}</b>: 호출자의 트랜잭션에 반드시 참여하고, 트랜잭션이 없으면
     * 예외로 실패한다. #37/#40 비고가 요구하는 "주문 생성과 하나의 상위 트랜잭션"을 애노테이션으로
     * 강제한 것이다. 기본값({@code REQUIRED})이면 누군가 이 메서드만 따로 호출했을 때 조용히 새
     * 트랜잭션이 열려 <b>주문 없이 포인트만 빠져나가는</b> 경로가 생긴다.
     *
     * <p><b>{@code FOR UPDATE} 로 읽는다</b>(사람 확인, #33 승인과 같은 방식). 원장에 남길
     * {@code balance_before} 를 알아야 하는데 조건부 UPDATE 는 영향 행 수만 주고 변경 직전 잔액을
     * 주지 않는다({@code ck_point_transaction_balance} 가 before/after/amount 정합을 검사한다).
     * 그래서 "읽고 → 검사하고 → 빼고 → 기록"이 필요하고, 그 사이 lost update 를 막으려면 읽는 시점에
     * 행을 잠가야 한다({@code @Version} 은 팀 정책상 사용하지 않는다). 지갑은 회원당 1행이라 잠금
     * 범위가 단일 행이고 회원끼리는 경합하지 않는다.
     *
     * <p><b>동시 요청</b>: 잔액 5,000원에 4,000원짜리 주문 두 건이 동시에 들어와도 잠금이 둘을
     * 직렬화하므로 뒤에 온 쪽이 잔액을 다시 읽어 402 를 받는다(#40 예외 처리 "동시 요청으로 잔액이
     * 부족해진 경우 한 요청만 성공한다").
     *
     * <p><b>이중 차감</b>은 {@code uk_point_transaction_order_type} (delivery_order_id,
     * transaction_type) 이 최종적으로 막는다. 정상 경로에서는 주문 생성이 멱등이라 여기까지 두 번
     * 오지 않지만, 제약이 백스톱으로 남아 있다.
     *
     * @param customerId 세션에서 확인된 고객 식별자
     * @param order      결제 대상 주문. 같은 트랜잭션에서 방금 저장된 managed 엔터티여야 한다.
     * @param fare       차감할 금액. <b>서버가 재계산한 값</b>이며 클라이언트 전달값이 아니다.
     * @param requestKey 주문의 요청 멱등키를 그대로 쓴다. 원장의 유니크는 (request_key,
     *                   transaction_type) 이라(V18) 같은 주문의 ORDER_REFUND 가 이 키를 공유할 수 있다.
     * @return 차감 후 잔액
     * @throws BusinessException     402 포인트 부족
     * @throws IllegalStateException 지갑 없음(회원가입 트랜잭션에서 함께 생성되므로 정합성 오류)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long payForOrder(Long customerId, DeliveryOrder order, long fare, String requestKey) {
        PointWallet wallet = pointWalletRepository.findByMemberIdForUpdate(customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));

        long balanceBefore = wallet.getBalance();
        if (balanceBefore < fare) {
            // 402 다(사람 확인). 프론트가 message 를 파싱하지 않고 "충전 화면으로" 분기할 수 있어야
            // 하는데, 이 엔드포인트의 409 는 이미 "진행 중 요청 있음"과 "요금 변경"이 쓰고 있다.
            throw new BusinessException(HttpStatus.PAYMENT_REQUIRED,
                    "포인트가 부족합니다. 잔액=%d, 필요=%d, 부족=%d"
                            .formatted(balanceBefore, fare, fare - balanceBefore));
        }

        wallet.debit(fare);
        pointTransactionRepository.save(PointTransaction.forOrder(
                wallet, PointTransactionType.ORDER_USE, fare, balanceBefore, requestKey, order));

        log.info("[포인트-주문결제] deliveryId={}, customerId={}, amount={}, balanceBefore={}, balanceAfter={}",
                order.getId(), customerId, fare, balanceBefore, wallet.getBalance());

        return wallet.getBalance();
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
     * 포인트 충전 취소(CUS-POINT-004, #34).
     *
     * <p>결제창에서 사용자가 결제를 포기했을 때 PENDING 충전 요청을 CANCELED 로 종료시킨다.
     * <b>잔액과 원장은 건드리지 않는다</b> — 승인된 적이 없으므로 되돌릴 증액이 없다.
     *
     * <p><b>승인과 달리 이 메서드에는 {@code @Transactional} 이 있다.</b> 승인은 PG 호출을 트랜잭션
     * 밖에 두려고 파사드와 {@link PointChargeApprover} 로 쪼갰지만, 취소는 <b>외부 호출이 없다</b>.
     * 승인 전이라 PG 에는 취소할 결제 자체가 존재하지 않기 때문이다({@code PaymentGateway.cancel}
     * 은 PAID 이후 망 취소·환불용이고 이 경로에서 부르지 않는다). 외부 호출이 없으면 트랜잭션을
     * 나눌 이유도 없어, {@code chargePointRequest} 와 같은 모양으로 한 트랜잭션에 둔다.
     *
     * <p><b>잠그고 읽는다</b>({@code findByIdForUpdate}). 취소와 승인이 같은 충전 건에 동시에 들어올
     * 수 있는데, 잠그지 않으면 둘 다 PENDING 을 읽고 취소는 CANCELED 로, 승인은 PAID 로 각각
     * 진행한다. 잠금이 둘을 직렬화하므로 진 쪽은 상태 재확인에서 걸린다 — 승인이 이기면 취소가
     * 409 를, 취소가 이기면 승인이 409 를 받는다. 잠금 순서 규칙({@code point_charge} →
     * {@code point_wallet})도 그대로 지킨다.
     *
     * <p><b>상태별 처리</b>는 셋으로 갈린다:
     *
     * <ul>
     *   <li>{@code PENDING} → CANCELED 로 전이. 정상 경로다.
     *   <li>{@code CANCELED}·{@code FAILED} → 전이하지 않고 <b>200 으로 현재 상태를 돌려준다</b>(멱등).
     *       둘 다 "요청이 종료됐고 잔액은 변하지 않았다"는 이 API 의 성공 조건을 이미 만족한다.
     *       따닥 클릭이나 네트워크 재시도가 오류로 보이지 않게 하려는 것이고,
     *       {@code confirmPointCharge} 가 이미 PAID 인 건을 200 으로 돌려주는 것과 같은 판단이다.
     *   <li>{@code PAID}·{@code REFUNDED} → 409. 이슈의 "이미 완료된 충전 요청은 실패 또는 취소
     *       상태로 변경하지 않는다"에 해당한다. 돈이 이미 움직였으므로 취소가 아니라 <b>환불</b>로
     *       풀어야 하는 사건이고, 환불은 아직 구현돼 있지 않다(#33 「일부러 하지 않은 것」).
     * </ul>
     *
     * <p><b>남은 위험</b>(#34, MVP 에서는 감수한다): 승인은 잠금 없이 사전 검증한 뒤 PG 를 부르므로,
     * 그 사이에 이 취소가 커밋되면 승인은 PG 응답을 받아 온 뒤 409 로 막히고 <b>승인 식별자가 DB 에
     * 남지 않는다</b> — 대사 조건에도 걸리지 않는 추적 불가 건이 된다. 모의 PG 는 돈이 움직이지 않아
     * 무해하다. 실 PG 를 붙일 때는 PG 호출 <b>전에</b> 상태를 선점(claim)해야 닫힌다 — 상태 값을
     * 늘리는 것만으로는 경쟁 창이 그대로다. 워크로그 {@code 2026-07-31-34-point-charge-cancel.md} 참조.
     *
     * <p><b>사유는 서버가 채운다</b>(사람 확인). 요청 바디를 받지 않는다 — 모의 결제에서 승인 전
     * 취소의 사유는 "사용자가 결제를 포기했다" 하나뿐이라 클라이언트가 알려 줄 정보가 없고,
     * 임의 문자열이 DB 로 들어오는 통로를 만들지 않는 편이 낫다. 실 PG 를 붙이면 PG 가 주는
     * 취소 코드를 서버가 여기에 채우게 된다.
     *
     * @param pointChargeId 취소 대상 충전 식별자
     * @param customerId    세션에서 확인된 고객 식별자
     * @throws BusinessException 404 없음 / 403 본인 아님 / 409 취소할 수 없는 상태
     */
    @Transactional
    public PointChargeCancelResponse cancelPointCharge(Long pointChargeId, Long customerId) {
        PointCharge pointCharge = pointChargeRepository.findByIdForUpdate(pointChargeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 충전 요청입니다."));

        // 소유 확인. customer 는 LAZY 프록시지만 식별자 접근은 조회를 유발하지 않는다.
        if (!pointCharge.getCustomer().getId().equals(customerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 충전 요청이 아닙니다.");
        }

        if (pointCharge.getStatus() == PointChargeStatus.PENDING) {
            // PENDING 인데 승인 식별자가 있다 = PG 승인은 받았는데 반영이 끊긴 건이다(#33).
            // 취소로 덮으면 "돈은 나갔는데 CANCELED" 가 되어 대사 대상에서 사라진다.
            if (pointCharge.getProviderPaymentKey() != null) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "결제 확인 중인 충전 요청입니다. 잠시 후 결제 내역을 확인해 주세요.");
            }
            pointCharge.cancel(CUSTOMER_CANCEL_REASON);
            log.info("[포인트-충전취소] pointChargeId={}, customerId={}, requestedAmount={}",
                    pointChargeId, customerId, pointCharge.getRequestedAmount());
        } else if (pointCharge.getStatus() != PointChargeStatus.CANCELED
                && pointCharge.getStatus() != PointChargeStatus.FAILED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "취소할 수 없는 상태입니다. status=" + pointCharge.getStatus());
        }

        return new PointChargeCancelResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getRequestedAmount(),
                currentBalance(customerId),
                pointCharge.getFailureReason());
    }

    /**
     * 취소 응답에 실을 현재 잔액. 지갑이 없으면 정합성 오류다 — 지갑은 회원가입 때 함께 생성된다.
     * 취소는 잔액을 바꾸지 않으므로 이 값은 취소 전 잔액과 같아야 한다.
     */
    private long currentBalance(Long customerId) {
        return pointWalletRepository.findByMemberId(customerId)
                .map(PointWallet::getBalance)
                .orElseThrow(() -> new IllegalStateException(
                        "지갑 정보가 없습니다. memberId=" + customerId));
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
