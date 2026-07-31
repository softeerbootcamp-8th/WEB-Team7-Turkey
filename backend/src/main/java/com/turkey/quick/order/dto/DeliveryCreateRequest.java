package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 배송요청 생성.
 *
 * status 를 받지 않는다 — 주문은 항상 WAITING 으로 시작하고, 이후 상태는 행위로만 바뀐다.
 * 직선거리도 받지 않는다 — 좌표에서 서버가 계산해야 요금 조작을 막을 수 있다.
 *
 * requestKey 는 클라이언트가 생성하는 UUID 로, 동일 요청 재전송을 막는 멱등키다
 * (delivery_order 의 (customer_id, request_key) 유니크). 결제하기 버튼 연타나
 * 네트워크 재시도로 주문이 두 건 생기는 것을 막는 유일한 장치이므로 필수로 받는다.
 */
@Schema(description = "배송요청 생성 요청")
public record DeliveryCreateRequest(

        @Schema(description = "요청 멱등키(클라이언트 생성 UUID). 재전송 시 같은 값을 보낸다.",
                example = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34")
        @NotBlank @Size(min = 36, max = 36)
        String requestKey,

        @Schema(description = "물품 종류")
        @NotNull
        ItemType itemType,

        @Schema(description = "픽업지")
        @NotNull @Valid
        AddressRequest pickup,

        @Schema(description = "도착지")
        @NotNull @Valid
        AddressRequest destination,

        @Schema(description = "보내는 사람")
        @NotNull @Valid
        ContactRequest sender,

        @Schema(description = "받는 사람")
        @NotNull @Valid
        ContactRequest recipient
) {
}
