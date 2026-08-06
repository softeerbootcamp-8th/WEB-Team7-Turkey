package com.turkey.quick.order.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 운임 산정 결과 분해. {@link com.turkey.quick.order.domain.OrderFareSnapshot} 의 컬럼 구성을 따른다.
 *
 * 견적(ESTIMATE)과 최종(FINAL) 스냅샷이 같은 구조를 쓰므로 응답 타입도 하나로 둔다.
 * 예상 요금과 최종 요금의 차이는 허용하기로 한 정책이라, 화면은 두 값을 각각 받아 비교할 수 있어야 한다.
 * totalFare 는 base + distance + surcharge 의 합이다(ck_order_fare_total).
 */
@Schema(description = "운임 분해(기본 + 거리 + 물품 할증)")
public record FareBreakdownResponse(

        @Schema(description = "산정에 사용한 요금 정책 버전", example = "v1")
        String policyVersion,

        @Schema(description = "산정 기준 거리(m)", example = "3200")
        Integer calculationDistanceMeters,

        @Schema(description = "기본 운임(원)", example = "3000")
        Long baseFare,

        @Schema(description = "거리 운임(원)", example = "2400")
        Long distanceFare,

        @Schema(description = "물품 종류 할증(원)", example = "1000")
        Long itemSurcharge,

        @Schema(description = "합계 운임(원)", example = "6400")
        Long totalFare
) {
    /**
     * 요금 스냅샷을 그대로 응답 형태로 옮긴다. ESTIMATE/FINAL 어느 스냅샷이든 컬럼 구성이 같아
     * 하나로 처리한다. 어느 스냅샷을 쓸지(견적 vs 최종)는 트랜잭션 경계가 있는 서비스 계층이 고른다.
     */
    public static FareBreakdownResponse from(OrderFareSnapshot snapshot) {
        return new FareBreakdownResponse(
                snapshot.getPolicyVersion(),
                snapshot.getCalculationDistanceMeters(),
                snapshot.getBaseFare(),
                snapshot.getDistanceFare(),
                snapshot.getItemSurcharge(),
                snapshot.getTotalFare());
    }
}
