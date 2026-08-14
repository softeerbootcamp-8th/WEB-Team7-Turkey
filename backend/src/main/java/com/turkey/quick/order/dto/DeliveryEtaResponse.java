package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 추적 화면이 <b>주기적으로</b> 부르는 도착 예정 시각(#447).
 *
 * <p><b>왜 별도 응답인가.</b> ETA 는 라이더가 이동하는 동안 계속 변해서 화면을 보고 있는 사이
 * 갱신돼야 하는데, 상세 조회({@link DeliveryDetailResponse})를 그 주기로 재조회하면 주소·연락처·
 * 타임라인·완료 인증·운임 분해까지 매번 실어 나른다. 폴링 경로에는 <b>변하는 값만</b> 담는다.
 *
 * <p><b>{@code status} 를 함께 담는 이유</b>는 ETA 가 픽업지 기준인지 도착지 기준인지가 같은 응답
 * 안에서 일관돼야 하기 때문이다({@code DeliveryRouteEstimator.targetOf}). 프론트가 상태를 다른
 * 조회에서 가져오면 전이 순간에 "픽업까지 3분"과 도착지 기준 값이 섞여 표시된다.
 *
 * <p><b>실패를 담지 않는다.</b> 라이더 위치 없음(배차 직후·TTL 만료)·경로 서버 장애·추적 불가
 * 상태는 전부 {@code estimatedArrivalAt = null} 인 200 이다(사람 확인, 2026-08-10). 폴링 경로라
 * 오류 응답을 내면 화면이 1분마다 실패를 처리해야 하는데, 이 값들은 대부분 <b>정상적으로</b>
 * 비어 있는 상황이라 오류로 다룰 것이 없다.
 */
@Schema(description = "배송 도착 예정 시각(폴링 전용)")
public record DeliveryEtaResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "현재 배송 상태. estimatedArrivalAt 이 어느 지점 기준인지 판단하는 근거다.")
        OrderStatus status,

        @Schema(description = "현재 향하는 지점의 도착 예정 시각(UTC). "
                + "픽업 전(ASSIGNED·MOVING_TO_PICKUP)에는 픽업지, 픽업 후(PICKED_UP·DELIVERING)에는 "
                + "도착지 기준이다 — 어느 쪽인지는 status 로 판단한다. "
                + "산정할 수 없으면(배차 전·완료·취소, 라이더 위치 없음, 경로 서버 장애) null.",
                example = "2026-07-28T02:47:00")
        LocalDateTime estimatedArrivalAt,

        @Schema(description = "라이더 현재 위치에서 estimatedArrivalAt 이 향하는 지점까지의 실제 도로 "
                + "경로 좌표(#532, 순서 보장). 직선거리 대신 이 경로를 지도에 그린다. "
                + "estimatedArrivalAt 과 같은 조건에서만 채워진다 — 산정할 수 없으면 null.")
        List<RoutePointResponse> path
) {

    /** 산정할 수 없는 경우. 오류가 아니라 정상 응답이다(위 javadoc 참고). */
    public static DeliveryEtaResponse unavailable(Long deliveryId, OrderStatus status) {
        return new DeliveryEtaResponse(deliveryId, status, null, null);
    }
}
