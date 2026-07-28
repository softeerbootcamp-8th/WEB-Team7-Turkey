package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 요금 견적 요청. 아직 주문을 만들지 않고 금액만 계산한다.
 *
 * 조회성 요청이지만 좌표 두 쌍을 실어야 해 GET 쿼리스트링으로는 다루기 나쁘고,
 * 개인 위치가 URL·액세스 로그에 남는 것도 피하고 싶어 POST 로 둔다.
 * 산정 기준 거리는 서버가 좌표로 계산한다 — 클라이언트가 거리를 넘기면 요금을 조작할 수 있다.
 */
@Schema(description = "요금 견적 요청")
public record FareQuoteRequest(

        @Schema(description = "물품 종류(할증 기준)")
        @NotNull
        ItemType itemType,

        @Schema(description = "픽업지")
        @NotNull @Valid
        AddressRequest pickupAddress,

        @Schema(description = "도착지")
        @NotNull @Valid
        AddressRequest destinationAddress
) {
}
