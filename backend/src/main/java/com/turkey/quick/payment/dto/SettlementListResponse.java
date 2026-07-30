package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 라이더 정산 내역 목록(페이지). */
@Schema(description = "라이더 정산 내역 목록(페이지)")
public record SettlementListResponse(

        @Schema(description = "조회 구간 정산 합계. 화면 상단 요약에 쓴다.", example = "184300")
        long totalAmount,

        @Schema(description = "정산 항목(최신순)")
        List<SettlementResponse> items,

        @Schema(description = "현재 페이지(0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "37")
        long totalElements
) {
}
