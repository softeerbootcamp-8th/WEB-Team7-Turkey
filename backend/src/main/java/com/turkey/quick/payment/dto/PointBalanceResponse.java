package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 포인트 잔액 응답(CUS-POINT-001, #31).
 *
 * <p>정본은 서버의 {@code point_wallet.balance} 다. 클라이언트가 보낸 잔액은 어떤 API 에서도
 * 신뢰하지 않으며, 결제·출금 시 잔액 검증은 서비스 계층의 조건부 UPDATE 가 다시 수행한다.
 * 즉 이 응답은 화면 표시용 스냅샷이지 차감 근거가 아니다.
 *
 * <p>고객·라이더가 같은 스키마를 공유한다 — 지갑은 member 와 PK 를 공유하는 회원 단위 개념이라
 * 역할별로 필드가 갈리지 않는다.
 */
@Schema(description = "포인트 잔액")
public record PointBalanceResponse(

        @Schema(description = "사용 가능한 포인트 잔액", example = "12500")
        long balance,

        @Schema(description = "잔액이 마지막으로 변경된 시각", example = "2026-07-29T04:12:33")
        LocalDateTime updatedAt
) {
}
