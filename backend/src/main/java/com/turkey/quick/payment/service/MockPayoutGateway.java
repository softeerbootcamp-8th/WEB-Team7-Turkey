package com.turkey.quick.payment.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MVP 모의 은행 이체. 실제 이체 대신 프로세스 안에서 결과를 만들어 낸다
 * ({@link MockPaymentGateway} 와 같은 역할·같은 자리).
 *
 * <p><b>왜 게이트웨이가 성공·실패를 결정하는가</b>: {@link MockPaymentGateway} 주석과 같은 이유다 —
 * 이체 성공·실패는 <b>은행의 판단</b>이지 호출자가 "성공/실패" 같은 요청 필드로 직접 통보할 것이
 * 아니다. 그 필드를 두면 실 은행 API 를 붙일 때 그 필드가 사라져야 하고 API 계약과 서비스 코드가
 * 함께 바뀐다. 판단을 이 구현체 안에 두면 실 연동은 <b>이 클래스를 갈아끼우는 것</b>으로 끝난다.
 *
 * <p><b>실패는 어떻게 재현하나</b>: {@code authToken} 이 {@link #DECLINE_TOKEN} 이면 거절로 다룬다
 * ({@link MockPaymentGateway#DECLINE_TOKEN} 과 같은 방식). 결제와 달리 이체는 클라이언트가 결제창을
 * 거쳐 이 토큰을 들고 오는 왕복이 없으므로, 모의 처리 화면/도구가 이 값을 직접 채워 보낸다.
 */
@Component
public class MockPayoutGateway implements PayoutGateway {

    /** 벤더 식별자. 실 연동 시 이체 이력에 남길 수 있는 자리(현재 rider_withdrawal 에는 컬럼 없음). */
    public static final String PROVIDER = "MOCK_BANK";

    /** 이 값으로 이체 요청하면 거절로 다룬다. 모의 처리 도구의 "송금 실패" 트리거. */
    public static final String DECLINE_TOKEN = "mock_decline";

    private static final Logger log = LoggerFactory.getLogger(MockPayoutGateway.class);

    @Override
    public Transfer transfer(TransferCommand command) {
        if (DECLINE_TOKEN.equals(command.authToken())) {
            log.info("[MOCK-BANK] 이체 거절 requestKey={}, amount={}",
                    command.withdrawalRequestKey(), command.amount());
            throw new PayoutGatewayException(PayoutGatewayException.FailureType.DECLINED,
                    "MOCK_DECLINED", "모의 송금이 거절되었습니다.");
        }

        String providerTransferKey =
                "mock_transfer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 이체 식별자를 반드시 로그에 남긴다. DB 반영이 실패해 롤백되면 이 로그가 그 이체가
        // 존재했다는 유일한 흔적이 된다(rider_withdrawal 에는 아직 이 값을 담을 컬럼이 없다).
        log.info("[MOCK-BANK] 이체 성공 requestKey={}, bankCode={}, amount={}, providerTransferKey={}",
                command.withdrawalRequestKey(), command.bankCode(), command.amount(), providerTransferKey);

        return new Transfer(providerTransferKey, command.amount(), LocalDateTime.now(ZoneOffset.UTC));
    }
}
