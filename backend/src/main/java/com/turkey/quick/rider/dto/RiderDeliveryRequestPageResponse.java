package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 배차 대기 콜 목록의 한 페이지(#60). keyset(커서) 방식이라 총 건수는 담지 않는다 — 매 요청마다
 * 최신 WAITING 목록을 다시 걸러 정렬한 뒤 그 안에서 자르므로, 총 건수를 세려면 별도 COUNT 조회가
 * 필요해 이 목록 자체의 비용과 안 맞다. {@code hasNext}만으로 "더 보기" 여부를 판단한다.
 */
@Schema(description = "배차 대기 콜 목록 한 페이지")
public record RiderDeliveryRequestPageResponse(

        @Schema(description = "이 페이지의 배송요청 목록")
        List<RiderDeliveryRequestSummaryResponse> items,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {
}
