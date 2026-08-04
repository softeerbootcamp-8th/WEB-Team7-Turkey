package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 라이더 운행 기록 목록(페이지).
 *
 * <p>정산 합계·수입 요약은 담지 않는다 — 금액 정보는 포인트 화면(/api/rider/points/*)으로
 * 분리했다(배송 기록·포인트 화면 분리). 이 응답은 순수 배송 기록만 다룬다.
 * Spring 의 Page 를 그대로 노출하지 않는 이유는 {@code DeliveryListResponse} 와 같다.
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
        long totalElements
) {
}
