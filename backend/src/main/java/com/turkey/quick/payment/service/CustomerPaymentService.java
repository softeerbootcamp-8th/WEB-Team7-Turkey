package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.util.Optional;
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

    /** MVP 는 실 PG 연동이 아니므로 provider 를 모의값으로 남긴다. 실 연동 시 벤더 식별자로 바뀐다. */
    private static final String MOCK_PAYMENT_PROVIDER = "MOCK";

    private final PointWalletRepository pointWalletRepository;
    private final PointChargeRepository pointChargeRepository;
    private final MemberRepository memberRepository;

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
                MOCK_PAYMENT_PROVIDER);

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

    private PointChargeResponse toResponse(PointCharge pointCharge) {
        return new PointChargeResponse(
                pointCharge.getId(),
                pointCharge.getStatus(),
                pointCharge.getRequestedAmount(),
                pointCharge.getPaymentMethod(),
                pointCharge.getRequestedAt());
    }
}
