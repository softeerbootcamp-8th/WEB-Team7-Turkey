package com.turkey.quick.payment.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MVP 모의 PG. 실제 결제 대신 프로세스 안에서 승인 결과를 만들어 낸다
 * ({@code member/service/LoggingSmsSender} 와 같은 역할·같은 자리).
 *
 * <p><b>왜 게이트웨이가 승인 여부를 결정하는가</b>: 승인 성공·실패는 <b>PG 의 판단</b>이지 클라이언트가
 * 요청 필드로 통보할 것이 아니다. 요청에 "모의 결제 결과" 같은 필드를 두면 실 PG 를 붙일 때 그 필드가
 * 사라져야 하고, 그러면 API 계약과 서비스 코드가 함께 바뀐다. 판단을 이 구현체 안에 두면
 * 실 연동은 <b>이 클래스를 갈아끼우는 것</b>으로 끝난다.
 *
 * <p><b>실패는 어떻게 재현하나</b>: {@code authToken} 이 {@link #DECLINE_TOKEN} 이면 거절로 다룬다.
 * 실 PG 에서도 이 토큰은 결제창을 통과해 받아 온 불투명한 값이고 그 의미를 해석하는 주체는 PG 다 —
 * 그 구조를 그대로 두면서 모의 결제 화면의 "결제 실패" 버튼이 이 값을 보내게 하면 실패 경로를
 * 화면에서 확인할 수 있다.
 *
 * <p><b>{@link #prepare} 는 외부 호출이 없다.</b> 모의 결제는 결제창을 열 준비가 필요 없다.
 * 준비 단계에 서버-투-서버 호출이 없는 실 PG(토스페이먼츠류)도 같은 모양이 된다.
 *
 * <p>MVP 에서 {@link #cancel} 은 쓰이지 않는다(충전 환불 기능 미구현). 포트가 요구하므로 구현만 둔다.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    /** {@code point_charge.payment_provider}(30자)에 남는 식별자. */
    public static final String PROVIDER = "MOCK";

    /** 이 값으로 승인 요청하면 거절로 다룬다. 모의 결제 화면의 "결제 실패" 버튼이 보내는 토큰. */
    public static final String DECLINE_TOKEN = "mock_decline";

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    @Override
    public Preparation prepare(PrepareCommand command) {
        log.info("[MOCK-PG] 결제 준비 orderKey={}, amount={}, method={}",
                command.chargeRequestKey(), command.amount(), command.paymentMethod());
        return new Preparation(PROVIDER, null, null, null);
    }

    @Override
    public Approval confirm(ConfirmCommand command) {
        if (DECLINE_TOKEN.equals(command.authToken())) {
            log.info("[MOCK-PG] 결제 거절 orderKey={}", command.chargeRequestKey());
            throw new PaymentGatewayException(PaymentGatewayException.FailureType.DECLINED,
                    "MOCK_DECLINED", "모의 결제가 거절되었습니다.");
        }

        String providerPaymentKey =
                "mock_pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 승인 식별자를 반드시 로그에 남긴다. DB 반영이 실패해 롤백되면 이 로그가 그 승인이 존재했다는
        // 유일한 흔적이 된다. 실 PG 구현체도 같은 이유로 승인 응답을 남겨야 한다.
        log.info("[MOCK-PG] 결제 승인 orderKey={}, amount={}, providerPaymentKey={}",
                command.chargeRequestKey(), command.expectedAmount(), providerPaymentKey);

        // 실 PG 구현체는 여기서 승인 응답의 금액이 expectedAmount 와 같은지 검증해야 한다.
        // 모의 승인은 요청 금액을 그대로 승인하므로 불일치가 발생하지 않는다.
        return new Approval(
                providerPaymentKey,
                command.expectedAmount(),
                "MOCK",
                "모의결제",
                LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public Cancellation cancel(CancelCommand command) {
        log.info("[MOCK-PG] 결제 취소 providerPaymentKey={}, amount={}",
                command.providerPaymentKey(), command.amount());
        return new Cancellation(
                "mock_refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                command.amount(),
                LocalDateTime.now(ZoneOffset.UTC));
    }
}
