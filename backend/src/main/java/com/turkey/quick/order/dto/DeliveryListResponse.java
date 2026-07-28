package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 이용기록 목록 응답.
 *
 * Spring 의 Page 를 그대로 노출하지 않는다 — Page 직렬화 형태는 버전에 따라 바뀌고
 * 화면이 쓰지 않는 필드(pageable, sort, first/last)가 그대로 프론트 타입에 새어나간다.
 */
@Schema(description = "배송요청 목록(페이지)")
public record DeliveryListResponse(

        @Schema(description = "목록 항목")
        List<DeliverySummaryResponse> items,

        @Schema(description = "현재 페이지(0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "37")
        long totalElements
) {
}
