package com.turkey.quick.payment.service;

import java.time.LocalDateTime;

/**
 * 외부 송금(은행 이체) 연동 포트 — {@link PaymentGateway} 와 같은 자리, 같은 역할(#90).
 *
 * <p>MVP 는 실제 은행 이체가 아닌 모킹 흐름이지만(CLAUDE.md 「확정된 결정」), 나중에 실제 이체 API 를
 * 붙일 때 서비스 계층 코드가 바뀌지 않도록 외부 호출 지점을 여기서 한 번 끊는다.
 *
 * <p><b>메서드가 하나뿐인 이유</b>: 결제는 준비(prepare)→승인(confirm)→환불(cancel) 세 단계가 벤더마다
 * 갈리지만(카카오페이류는 준비 단계에 실제 호출이 있고 토스류는 없음, {@link PaymentGateway#prepare}
 * 참고), 은행 송금 API 는 통상 "이체 실행" 한 번의 서버-투-서버 호출로 끝난다. 클라이언트가 결제창을
 * 거쳐 돌아오는 왕복도 없다 — 그래서 {@code authToken} 같은 클라이언트발 인증 값이 없고, 이 호출은
 * 우리 서버가 능동적으로 시작한다.
 *
 * <p><b>이 포트가 담당하지 않는 것</b>
 * <ul>
 *   <li>{@code rider_withdrawal} 행 생성·상태 전이 — 도메인({@code RiderWithdrawal})과 서비스 소관이다.
 *   <li>실패 시 포인트 복구와 {@code WITHDRAWAL_REFUND} 원장 기록 — 확정 트랜잭션 안에서 서비스가 한다.
 * </ul>
 *
 * <p><b>트랜잭션 경계 주의</b>: 외부 네트워크 호출이므로 DB 트랜잭션 안에서 부르면 커넥션을 응답
 * 대기 시간만큼 잡는다. 호출 → 결과 확정 → 짧은 트랜잭션으로 DB 반영 순서를 지키는 것은 서비스
 * 계층 책임이다({@code CustomerPaymentService#confirmPointCharge} 와 같은 구조).
 */
public interface PayoutGateway {

    /**
     * 이체 실행.
     *
     * <p>부분 이체는 없다 — 요청 금액 전액을 이체하거나 실패한다.
     *
     * @throws PayoutGatewayException 이체 거절 또는 통신 실패
     */
    Transfer transfer(TransferCommand command);

    /**
     * 이체 요청.
     *
     * <p>계좌 정보는 {@code rider_withdrawal} 의 요청 시점 스냅샷을 그대로 넘긴다 — 이체는 등록
     * 계좌가 아니라 그 스냅샷을 대상으로 해야, 요청 이후 계좌를 바꿔도 이미 진행 중인 이체가
     * 흔들리지 않는다.
     *
     * @param withdrawalRequestKey 우리 쪽 멱등키. 외부에서는 이체 주문 식별자로 쓰인다.
     * @param bankCode              은행 코드
     * @param maskedAccountNumber   마스킹 계좌번호. 평문 계좌번호는 스냅샷에 없어 포트로도 못 넘긴다
     *                              (실 연동 시 계좌 원문 보관·전달 방식은 별도 결정 필요).
     * @param accountHolderName     예금주명
     * @param amount                이체 금액(포인트=원)
     * @param authToken             모의 이체 결과를 통제하는 값. {@link MockPayoutGateway#DECLINE_TOKEN}
     *                              을 보내면 거절 경로를 확인할 수 있다 — 실 은행 API 에는 대응하는
     *                              필드가 없고, MVP 모의 화면/도구가 채워 보낸다.
     */
    record TransferCommand(
            String withdrawalRequestKey,
            String bankCode,
            String maskedAccountNumber,
            String accountHolderName,
            long amount,
            String authToken) {
    }

    /**
     * 이체 성공 결과.
     *
     * @param providerTransferKey 벤더 이체 식별자. 중복 이체 방지·대사의 근거가 된다.
     * @param transferredAmount   실제 이체된 금액. 부분 이체가 없으므로 요청 금액과 같다.
     * @param transferredAt       벤더 처리 시각(UTC)
     */
    record Transfer(
            String providerTransferKey,
            long transferredAmount,
            LocalDateTime transferredAt) {
    }

    /**
     * 송금 연동 실패. {@link PaymentGateway.PaymentGatewayException} 과 같은 이유로
     * {@link FailureType} 을 들고 있다 — 호출자가 "재시도할 것인가"와 "출금을 FAILED 로 확정할
     * 것인가"를 갈라야 한다.
     */
    class PayoutGatewayException extends RuntimeException {

        /**
         * <ul>
         *   <li>{@code DECLINED} — 은행이 이체를 거절했다(예금주 불일치, 계좌 정지 등). 재시도해도
         *       같은 결과이므로 출금 건을 FAILED 로 확정하고 포인트를 복구한다.
         *   <li>{@code TIMEOUT} — 응답을 받지 못했다. <b>이체가 성사됐는지 알 수 없는 상태</b>라
         *       PENDING 을 유지한다 — FAILED 로 확정하면 실제로는 이체된 건의 포인트를 다시
         *       내주는 이중 지급이 될 수 있다.
         *   <li>{@code PROVIDER_ERROR} — 벤더 측 오류·잘못된 요청. 이체는 일어나지 않았다고 보고
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

        public PayoutGatewayException(FailureType failureType, String providerErrorCode,
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
