package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 요금 견적 응답. 이 시점에는 주문도 운임 스냅샷도 만들지 않는다 —
 * ESTIMATE 스냅샷은 주문 생성 트랜잭션에서 남는다.
 *
 * 따라서 여기서 받은 금액은 확정값이 아니며, 실제 청구는 주문 생성 응답의 estimatedFare 가 기준이다.
 */
@Schema(description = "요금 견적 결과")
public record FareQuoteResponse(

        @Schema(description = "견적 운임 분해")
        FareBreakdownResponse fare,

        @Schema(description = "예상 소요 시간(분). 화면의 도착 예정 문구에 쓴다.", example = "25")
        Integer estimatedMinutes
) {
}
