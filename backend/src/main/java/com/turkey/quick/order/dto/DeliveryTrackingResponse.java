package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송 추적 화면의 초기 스냅샷.
 *
 * 라이더의 실시간 좌표는 여기 담지 않는다 — 위치는 location 도메인의 SSE 스트림이 전달한다.
 * 이 응답은 화면 진입 시 한 번 그릴 상태·타임라인·상대 정보만 제공한다.
 *
 * <p><b>ETA 는 이 응답으로만 전달한다</b>(#421, 사람 확인 2026-08-06). SSE 프레임마다 재계산하면
 * 라이더당 5초 주기로 라우팅 호출이 발생하는데, ETA 가 5초마다 의미 있게 바뀌지 않아 비용만 는다.
 * 대신 상태 전이 SSE 신호(#399)와 프론트 백업 폴링(약 1분, #422)이 <b>둘 다 이 API 재조회</b>로
 * 수렴하므로, 한 번의 재조회로 상태와 ETA 가 함께 갱신된다.
 *
 * <p><b>예상 경로 좌표는 싣지 않는다</b>(사람 확인, 2026-08-07). 라우팅 응답에 경로가 함께 오지만
 * 고객에게 ETA 만 보여주기로 했다 — 지도에 선을 그리면 마커(SSE, 5초)와 경로선(폴링, 1분)의 갱신
 * 주기가 달라 라이더가 경로를 벗어난 것처럼 보이는 문제가 따라온다(#423 「알려진 리스크」).
 * 필요해지면 <b>필드를 추가</b>하면 된다 — 계약에서 안전한 방향은 추가지 제거가 아니라서, 아무도
 * 쓰지 않는 필드를 미리 싣지 않는다.
 */
@Schema(description = "배송 추적 스냅샷")
public record DeliveryTrackingResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "현재 배송 상태")
        OrderStatus status,

        @Schema(description = "상태 전이 타임라인(도달한 단계만)")
        List<DeliveryStatusStepResponse> steps,

        @Schema(description = "배차된 라이더 이름. 배차 전이면 null.", example = "박라이더")
        String riderName,

        @Schema(description = "배차된 라이더 연락처. 배차 전이면 null.", example = "010-9876-5432")
        String riderPhoneNumber,

        @Schema(description = "현재 향하는 지점의 도착 예정 시각(UTC). 산정 불가하면 null. "
                + "픽업 전(ASSIGNED·MOVING_TO_PICKUP)에는 픽업지, 픽업 후(PICKED_UP·DELIVERING)에는 "
                + "도착지 기준이다 — 어느 쪽인지는 status 로 판단한다.",
                example = "2026-07-28T02:47:00")
        LocalDateTime estimatedArrivalAt,

        @Schema(description = "결제 금액(원)", example = "6400")
        Long totalFare
) {
}
