package com.turkey.quick.rider.dto;

import com.turkey.quick.rider.domain.OperatingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 라이더의 현재 운행 상태.
 *
 * 프론트는 이 값으로 화면을 고른다(UNAVAILABLE→홈, AVAILABLE→콜 목록, BUSY→진행 배송)
 * 및 위치 전송 주기를 정하므로, 재로그인·새로고침 직후 가장 먼저 부르는 응답이다.
 */
@Schema(description = "라이더 운행 상태")
public record RiderOperatingStatusResponse(

        @Schema(description = "운행 상태")
        OperatingStatus operatingStatus,

        @Schema(description = "상태가 바뀐 시각(UTC)", example = "2026-07-28T01:55:00")
        LocalDateTime statusChangedAt,

        @Schema(description = "진행 중 배송 식별자. BUSY 가 아니면 null.", example = "1024")
        Long currentDeliveryId
) {
}
