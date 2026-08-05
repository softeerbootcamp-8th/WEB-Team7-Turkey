package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 라이더 운행 기록 API 계약(문서 전용). 매핑·바인딩·인증 결합은 구현체에 둔다(#245 스타일).
 *
 * <p>완료된 배송을 조회하는 읽기 전용 계약이다. 목록(#70)은 금액을 담지 않지만 — 배송 기록과
 * 포인트 화면이 분리되어 금액은 포인트 API(/api/rider/points/*) 소관이다 — 상세(#71)는 이슈 명세에
 * 따라 확정 운임(FINAL 스냅샷)과 정산 내역을 함께 담는다(정산 확인 화면이라 목록과 정책이 갈린다).
 */
@Tag(name = "rider-history", description = "라이더 운행 기록 — 완료 배송 목록·상세")
public interface RiderDeliveryHistoryApi {

    @Operation(
            operationId = "getDeliveryHistories",
            summary = "운행 기록 목록",
            description = "본인에게 배정되어 완료된 배송을 완료 시각 최신순으로 페이지 조회한다. 기록이 없으면 빈 목록을 반환한다.")
    ApiResponse<RiderDeliveryHistoryListResponse> getDeliveryHistories(
            AuthenticatedRider rider,

            @Parameter(description = "페이지(0부터)", example = "0")
            int page,

            @Parameter(description = "페이지 크기", example = "10")
            int size);

    @Operation(
            operationId = "getDeliveryHistoryDetail",
            summary = "운행 기록 상세",
            description = "본인이 완료한 배송 한 건의 출발지·도착지·물품, 상태 전이 타임라인, 확정 운임(FINAL 스냅샷), "
                    + "정산 내역, 완료 인증을 조회한다. 존재하지 않거나 본인이 완료한 배송이 아니면 404 다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "존재하지 않거나 본인이 완료한 배송이 아님")
    })
    ApiResponse<RiderDeliveryHistoryDetailResponse> getDeliveryHistoryDetail(
            AuthenticatedRider rider,

            @Parameter(description = "운행 기록(배송요청) 식별자", example = "1024")
            Long deliveryId);
}
