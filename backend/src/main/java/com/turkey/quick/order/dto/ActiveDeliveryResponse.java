package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 고객 홈의 진행 중 배송 요약(#100/#208). 홈은 "간단한 정보"만 필요해서 상태·출발지·도착지·요청
 * 시각만 싣는다 — 목록 항목({@link DeliverySummaryResponse})과 달리 <b>요금을 담지 않는다.</b>
 * 요금을 실으면 운임 스냅샷 조회가 따라오고, 스냅샷 불변식 위반 시 홈 진입이 500 이 되는데(사람 확인,
 * 2026-08-06) 홈 요약이 그 위험을 질 이유가 없다.
 *
 * <p>진행 중 배송이 없으면 이 DTO 자체를 만들지 않고 {@code ApiResponse.ok(null)} 로 응답한다
 * (data=null, success=true) — 이슈 #100 의 "빈 결과".
 */
@Schema(description = "진행 중 배송 요약(없으면 응답 data 가 null)")
public record ActiveDeliveryResponse(

        @Schema(description = "배송요청 식별자", example = "1024")
        Long deliveryId,

        @Schema(description = "배송 상태(WAITING~DELIVERING)")
        OrderStatus status,

        @Schema(description = "물품 종류")
        ItemType itemType,

        @Schema(description = "픽업지 도로명 주소", example = "서울 강남구 테헤란로 152")
        String pickupRoadAddress,

        @Schema(description = "도착지 도로명 주소", example = "서울 송파구 올림픽로 300")
        String destinationRoadAddress,

        @Schema(description = "요청 시각(UTC)", example = "2026-07-28T02:10:00")
        LocalDateTime requestedAt
) {
    public static ActiveDeliveryResponse from(DeliveryOrder order) {
        return new ActiveDeliveryResponse(
                order.getId(),
                order.getStatus(),
                order.getItemType(),
                order.getPickup().getRoadAddress(),
                order.getDestination().getRoadAddress(),
                order.getRequestedAt());
    }
}
