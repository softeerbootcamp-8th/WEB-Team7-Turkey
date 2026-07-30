package com.turkey.quick.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 포인트 거래 내역 목록(페이지).
 *
 * <p>order 도메인과 같은 이유로 Spring 의 Page 를 그대로 노출하지 않는다 — 직렬화 형태가
 * 버전에 따라 바뀌고 화면이 쓰지 않는 필드가 프론트 타입에 새어나간다.
 */
@Schema(description = "포인트 거래 내역 목록(페이지)")
public record PointTransactionListResponse(

        @Schema(description = "현재 잔액. 목록 화면 상단 카드가 잔액을 따로 조회하지 않도록 함께 담는다.",
                example = "4000")
        long balance,

        @Schema(description = "거래 내역 항목(최신순)")
        List<PointTransactionResponse> items,

        @Schema(description = "현재 페이지(0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "37")
        long totalElements
) {
}
