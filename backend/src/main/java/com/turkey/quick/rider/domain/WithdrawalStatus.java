package com.turkey.quick.rider.domain;

/**
 * 라이더 출금 요청 처리 상태.
 * PENDING 에서 시작해 COMPLETED(송금 성공) 또는 FAILED(송금 실패 + 포인트 복구)로 끝난다.
 * 상태별 processed_at·points_restored 조합은 DB ck_rider_withdrawal_state_values 와
 * RiderWithdrawal 의 전이 메서드가 함께 강제한다.
 */
public enum WithdrawalStatus {
    PENDING,
    COMPLETED,
    FAILED
}
