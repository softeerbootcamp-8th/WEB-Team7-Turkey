package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 라이더 운행 기록 API 계약(문서 전용). 매핑·바인딩·인증 결합은 구현체에 둔다(#245 스타일).
 *
 * <p>완료된 배송의 목록을 최신순으로 조회하는 읽기 전용 계약이다. 금액(정산액·운임)은 담지 않는다 —
 * 배송 기록과 포인트 화면이 분리되어 금액은 포인트 API(/api/rider/points/*) 소관이다.
 *
 * <p>운행 기록 <b>상세</b>(GET /{deliveryId}: 운임 분해·정산액·타임라인·완료 인증)는 이 이슈(#70)
 * 범위 밖이라 여기 두지 않는다. 상세 이슈에서 이 인터페이스에 메서드를 추가하고
 * {@code RiderDeliveryHistoryDetailResponse}(이미 존재)를 재사용한다.
 */
@Tag(name = "rider-history", description = "라이더 운행 기록 — 완료 배송 목록")
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
}
