package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 출금 내역 목록(페이지). */
@Schema(description = "포인트 출금 내역 목록(페이지)")
public record WithdrawalListResponse(

        @Schema(description = "출금 항목(최신순)")
        List<WithdrawalResponse> items,

        @Schema(description = "현재 페이지(0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "5")
        long totalElements
) {
}
