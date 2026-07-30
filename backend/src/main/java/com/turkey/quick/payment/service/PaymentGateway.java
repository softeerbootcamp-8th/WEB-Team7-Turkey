package com.turkey.quick.payment.service;

import com.turkey.quick.payment.domain.PaymentMethod;
import java.time.LocalDateTime;

/**
 * 외부 결제(PG) 연동 포트 — 벤더 중립 최소 스펙(CUS-POINT-002).
 *
 * <p>MVP 는 실 PG 연동이 아닌 모킹 흐름이지만(CLAUDE.md 「확정된 결정」), 나중에 실제 결제창을
 * 붙일 때 서비스 계층 코드가 바뀌지 않도록 외부 호출 지점을 여기서 한 번 끊는다.
 * {@code member/service/SmsSender} 와 같은 자리·같은 역할이다 — 그래서 별도 {@code gateway}
 * 패키지를 만들지 않고 소비자(서비스 계층)와 같은 패키지에 둔다.
 *
 * <p><b>벤더 중립을 택한 이유</b>: 토스페이먼츠·카카오페이 등 어느 한 벤더 스펙을 그대로 옮기면
 * 그 벤더의 용어(paymentKey / tid / pg_token)와 단계 구성이 우리 도메인에 새어 든다. 대신
 * {@code point_charge} 테이블이 이미 갖고 있는 세 축(준비 → 승인 → 환불)만 노출하고, 벤더별
 * 식별자는 모두 {@code providerXxx} 라는 불투명 문자열로 받는다. 트레이드오프는 실 PG 를 붙일 때
 * 이 포트와 벤더 API 사이에 매핑 코드가 한 겹 필요하다는 점이다 — 그 비용은 구현체 안에 갇힌다.
 *
 * <p><b>메서드가 3개뿐인 이유</b>: 조회(inquiry)·웹훅 수신은 넣지 않았다. 조회는 승인 응답을
 * 신뢰하는 동기 흐름에서는 불필요하고, 웹훅은 엔드포인트·서명 검증이라 포트가 아니라 컨트롤러
 * 소관이다. 실제로 필요해지는 시점에 추가한다.
 *
 * <p><b>이 포트가 담당하지 않는 것</b>
 * <ul>
 *   <li>{@code point_charge} 행 생성·상태 전이 — 도메인({@code PointCharge})과 서비스 소관이다.
 *   <li>지갑 잔액 증가와 {@code point_transaction} 원장 기록 — 승인 트랜잭션 안에서 서비스가 한다.
 *   <li>멱등키 발급 — {@code chargeRequestKey} 는 클라이언트가 만들어 보내고
 *       {@code uk_point_charge_customer_request} 가 재전송을 막는다. 포트는 그 값을 주문 식별자로
 *       외부에 전달할 뿐 새로 만들지 않는다.
 *   <li>PENDING 상태의 취소 — 아직 승인된 결제가 없으므로 외부 호출 없이 우리 쪽 상태만 바꾼다
 *       ({@code PointCharge.cancel()}). {@link #cancel(CancelCommand)} 는 PAID 이후의 환불용이다.
 * </ul>
 *
 * <p><b>트랜잭션 경계 주의</b>: 세 메서드 모두 외부 네트워크 호출이므로 DB 트랜잭션 안에서 부르면
 * 커넥션을 응답 대기 시간만큼 잡는다. 호출 → 결과 확정 → 짧은 트랜잭션으로 DB 반영 순서를
 * 지키는 것은 서비스 계층 책임이다.
 */
public interface PaymentGateway {

    /**
     * 결제 준비. 결제 수단·금액을 외부에 등록하고 결제창 진입 정보를 받는다.
     *
     * <p>이 단계는 잔액을 변화시키지 않으며, 우리 쪽에서는 {@code point_charge} 를 PENDING 으로
     * 남기는 시점에 대응한다. 준비 단계가 없는 벤더(결제창을 먼저 띄우고 승인만 서버가 하는 방식)를
     * 붙일 경우 구현체는 외부 호출 없이 {@link Preparation} 을 조립해 돌려주면 된다 — 그래야
     * 서비스 계층의 호출 순서가 벤더에 따라 갈리지 않는다.
     *
     * <p><b>벤더별로 이 메서드가 하는 일이 다르다</b>(사람 확인, 2026-07-30). 카카오페이류는 준비가
     * 실제 서버-투-서버 호출이고({@code ready} → {@code tid} 발급) 그 식별자를 승인까지 보관해야
     * 하므로 이 메서드가 반드시 필요하다. 반면 토스페이먼츠류는 준비 단계에 PG 호출이 없고
     * 우리 DB 에 orderId·amount 를 확정 저장하는 것이 준비의 전부다 — 그 경우 이 메서드는
     * 외부 호출 없이 값만 조립한다. 어느 쪽으로 갈지 미정이라 포트에는 남겨 둔다.
     *
     * <p><b>「충전 준비」 이슈(#32, CUS-POINT-002)는 이 메서드를 부르지 않아도 된다.</b> MVP 는
     * 모킹이라 PG 호출이 0 회이고, 금액 검증 → 요청번호 발급 → PENDING 저장은 전부 서비스 계층
     * 작업이다. 이 메서드는 실 PG 를 붙일 때 그 흐름에 끼워 넣을 자리다.
     *
     * @throws PaymentGatewayException 준비 실패
     */
    Preparation prepare(PrepareCommand command);

    /**
     * 결제 승인. 성공하면 우리 쪽은 PENDING → PAID 전이와 잔액 증가를 같은 트랜잭션에서 수행한다.
     *
     * <p>부분 승인은 없다. 구현체는 승인 금액이 {@link ConfirmCommand#expectedAmount()} 와 다르면
     * 성공으로 취급하지 말고 {@link PaymentGatewayException} 을 던져야 한다 — 금액 불일치를 그대로
     * 통과시키면 DB 의 {@code ck_point_charge_state_values}(PAID 는 approved = requested) 위반으로
     * 뒤늦게 터진다.
     *
     * @throws PaymentGatewayException 승인 거절 또는 통신 실패
     */
    Approval confirm(ConfirmCommand command);

    /**
     * 승인된 결제의 전액 환불. 부분 환불은 스키마상 존재하지 않는다
     * ({@code ck_point_charge_refund_amount}).
     *
     * <p>중복 환불은 {@code uk_point_charge_provider_refund} 가 DB 에서도 막지만, 구현체가 같은
     * 결제에 대한 재요청을 어떻게 다루는지는 벤더마다 다르다. 이미 환불된 건에 대한 재요청이
     * 성공으로 오는 벤더라면 기존 환불 식별자를 그대로 담아 돌려주는 편이 서비스 계층에서 다루기 쉽다.
     *
     * @throws PaymentGatewayException 환불 실패
     */
    Cancellation cancel(CancelCommand command);

    /**
     * 결제 준비 요청.
     *
     * <p>고객 식별자를 담지 않는다 — 외부에 회원 정보를 넘길 이유가 없고, 지갑의 주인은 세션에서
     * 이미 확정돼 있다. 외부에 나가는 주문 식별자는 {@code chargeRequestKey} 하나로 충분하다.
     *
     * @param chargeRequestKey 우리 쪽 멱등키(UUID 36자). 외부에서는 주문 식별자로 쓰인다.
     * @param amount           결제 금액(원). 양수만 유효하다.
     * @param paymentMethod     결제 수단
     * @param orderName        결제창에 표시할 주문명(예: "포인트 10,000원 충전")
     */
    record PrepareCommand(
            String chargeRequestKey,
            long amount,
            PaymentMethod paymentMethod,
            String orderName) {
    }

    /**
     * 결제 준비 결과.
     *
     * @param provider               벤더 식별자. {@code point_charge.payment_provider}(30자)에 저장된다.
     * @param providerTransactionKey 벤더가 준비 단계에 발급한 거래 식별자. 승인 때 되돌려줘야 한다.
     *                               준비 단계가 없는 벤더에서는 null.
     * @param paymentPageUrl         결제창 URL 또는 리다이렉트 대상. 클라이언트 SDK 로 결제창을 여는
     *                               방식이면 null.
     * @param expiresAt              준비 정보 만료 시각(UTC). 벤더가 알려주지 않으면 null.
     */
    record Preparation(
            String provider,
            String providerTransactionKey,
            String paymentPageUrl,
            LocalDateTime expiresAt) {
    }

    /**
     * 결제 승인 요청.
     *
     * @param chargeRequestKey       준비 때 넘긴 주문 식별자
     * @param providerTransactionKey {@link Preparation} 에서 받은 값(없는 벤더면 null)
     * @param authToken              결제창을 통과한 뒤 클라이언트가 받아 온 인증 값
     *                               (벤더에 따라 paymentKey·pg_token 등). 벤더 용어를 새지 않게
     *                               중립 이름으로 받는다.
     * @param expectedAmount         우리가 기억하는 요청 금액. 구현체는 이 값과 승인 금액이 같은지
     *                               반드시 검증한다.
     */
    record ConfirmCommand(
            String chargeRequestKey,
            String providerTransactionKey,
            String authToken,
            long expectedAmount) {
    }

    /**
     * 결제 승인 결과. 필드가 {@code point_charge} 의 승인 관련 컬럼과 1:1 로 대응한다.
     *
     * @param providerPaymentKey  벤더 승인 식별자(100자). 유니크 제약이 중복 승인을 막는 값이다.
     * @param approvedAmount      승인 금액. 전액 승인이므로 요청 금액과 같다.
     * @param issuerCode          카드사·은행 코드(20자). 없으면 null.
     * @param maskedPaymentMethod 마스킹된 결제수단 표시 문자열(100자). 없으면 null.
     * @param approvedAt          벤더 승인 시각(UTC)
     */
    record Approval(
            String providerPaymentKey,
            long approvedAmount,
            String issuerCode,
            String maskedPaymentMethod,
            LocalDateTime approvedAt) {
    }

    /**
     * 환불 요청.
     *
     * @param providerPaymentKey 환불 대상 결제의 승인 식별자
     * @param amount             환불 금액. 전액 환불만 있으므로 승인 금액과 같아야 한다.
     * @param reason             환불 사유. {@code point_charge.refund_reason}(255자)에 저장된다.
     */
    record CancelCommand(
            String providerPaymentKey,
            long amount,
            String reason) {
    }

    /**
     * 환불 결과.
     *
     * @param providerRefundKey 벤더 환불 식별자(100자). 유니크 제약이 중복 환불을 막는 값이다.
     * @param canceledAmount    환불된 금액
     * @param canceledAt        벤더 환불 시각(UTC)
     */
    record Cancellation(
            String providerRefundKey,
            long canceledAmount,
            LocalDateTime canceledAt) {
    }

    /**
     * PG 연동 실패. 구현체가 던지고, HTTP 상태 변환은 서비스·컨트롤러 계층이 맡는다
     * ({@code SmsSendFailedException} 과 같은 규약).
     *
     * <p>{@link FailureType} 을 들고 있는 이유는 호출자가 "재시도할 것인가"와 "충전 건을 FAILED 로
     * 확정할 것인가"를 갈라야 하기 때문이다. 메시지 문자열만 주면 그 판단을 문자열 파싱으로 하게 된다.
     */
    class PaymentGatewayException extends RuntimeException {

        /**
         * 실패 성격.
         *
         * <ul>
         *   <li>{@code DECLINED} — 벤더가 결제를 거절했다(한도 초과, 카드 오류 등). 재시도해도 같은
         *       결과이므로 충전 건을 FAILED 로 확정한다.
         *   <li>{@code TIMEOUT} — 응답을 받지 못했다. <b>결제가 성사됐는지 알 수 없는 상태</b>라
         *       PENDING 을 유지하고, 같은 요청을 그대로 재전송하면 이중 결제가 될 수 있다.
         *   <li>{@code PROVIDER_ERROR} — 벤더 측 오류·잘못된 요청. 결제는 일어나지 않았다고 보고
         *       실패로 다룬다.
         * </ul>
         */
        public enum FailureType {
            DECLINED,
            TIMEOUT,
            PROVIDER_ERROR
        }

        private final FailureType failureType;

        /** 벤더가 준 오류 코드. 없으면 null. 로그·failure_reason 기록용이며 분기 근거로 쓰지 않는다. */
        private final String providerErrorCode;

        public PaymentGatewayException(FailureType failureType, String providerErrorCode,
                                       String message) {
            super(message);
            this.failureType = failureType;
            this.providerErrorCode = providerErrorCode;
        }

        public FailureType getFailureType() {
            return failureType;
        }

        public String getProviderErrorCode() {
            return providerErrorCode;
        }
    }
}
