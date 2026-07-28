package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 라이더 운행 기록 목록(페이지) + 주간 수입 요약.
 *
 * 화면 상단의 주간 총수입을 별도 API 로 나누지 않고 같은 응답에 넣는다 — 항상 함께 그려지고,
 * 두 번 호출하면 페이지 이동 중 두 값의 기준 시점이 어긋난다.
 */
@Schema(description = "라이더 운행 기록 목록(페이지)")
public record RiderDeliveryHistoryListResponse(

        @Schema(description = "목록 항목")
        List<RiderDeliveryHistoryItemResponse> items,

        @Schema(description = "현재 페이지(0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "128")
        long totalElements,

        @Schema(description = "이번 주 정산 합계(원)", example = "184000")
        long weeklySettlementTotal
) {
}
