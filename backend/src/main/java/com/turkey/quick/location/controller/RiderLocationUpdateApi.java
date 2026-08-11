package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "rider-location", description = "라이더 현재 위치 갱신")
public interface RiderLocationUpdateApi {

    /**
     * 요청 본문은 <b>좌표·측정 시각·정확도만</b> 담는다. 배송 식별자는 받지 않는다 — 서버가
     * "이 라이더가 수행 중인 배송"을 DB 로 풀어 추적 채널을 정한다
     * ({@code RiderLocationService}). 이유는 {@link RiderLocationUpdateRequest} javadoc 참고.
     */
    @Operation(operationId = "updateRiderLocation", summary = "라이더 현재 위치 갱신",
               description = "좌표만 전송한다. 추적 대상 배송은 서버가 판정한다. 운행 중(AVAILABLE·BUSY)이 아니면 409.")
    ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request,
                                          AuthenticatedRider rider);

}
