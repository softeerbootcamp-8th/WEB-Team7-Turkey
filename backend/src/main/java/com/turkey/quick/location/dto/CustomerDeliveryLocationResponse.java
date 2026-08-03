package com.turkey.quick.location.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 고객 위치 폴링 API(#311, 부하테스트용)의 응답.
 *
 * <p>SSE {@code init} 이벤트와 필드 구성을 맞추려던 원래 계획(이슈 본문)은 성립하지 않는다 —
 * 그 이벤트({@code TrackingInitPayload})는 #77에서 만들어졌다가 위치 추적 단순화(#289, 커밋
 * {@code e04b35a})로 삭제됐고, #317이 SSE 팬아웃을 되돌릴 때도 이 부분은 복원하지 않았다(사람 확인,
 * 2026-08-03). 그래서 이 응답은 폴링 arm 전용이며, 나중에 SSE에 init 스냅샷이 다시 생기면 그때
 * 공유 여부를 판단한다.
 *
 * @param location 라이더의 마지막으로 알려진 위치. Redis에 없으면(미전송·TTL 만료) {@code null}이다
 *                 — 오류가 아니라 SSE init과 같은 "위치 없음" 정책이다.
 */
@Schema(description = "고객 위치 폴링 스냅샷(부하테스트용)")
public record CustomerDeliveryLocationResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "현재 배송 상태")
        OrderStatus status,

        @Schema(description = "라이더의 마지막으로 알려진 위치. 없으면 null")
        LocationPayload location
) {
}
