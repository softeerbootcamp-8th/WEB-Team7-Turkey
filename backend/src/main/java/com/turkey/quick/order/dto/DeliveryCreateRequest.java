package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 *
 * <p>estimatedFare 는 <b>대조용이지 결제 금액이 아니다</b>(#37). 요금은 서버가 좌표로 다시
 * 계산하고 그 값으로 차감하며, 이 필드는 "화면이 사용자에게 얼마라고 안내했는가"를 알려 준다.
 * 둘이 다르면 주문을 만들지 않는다 — 5,000원을 보고 결제 버튼을 눌렀는데 5,500원이 빠져나가는
 * 상황을 막기 위해서다(사람 확인). 요금 정책이 바뀌었거나 화면이 낡은 견적을 들고 있다는 뜻이므로,
 * 화면은 요금을 다시 받아 사용자에게 보여준 뒤 재요청해야 한다.
 */
@Schema(description = "배송요청 생성 요청")
public record DeliveryCreateRequest(

        @Schema(description = "요청 멱등키(클라이언트 생성 UUID). 재전송 시 같은 값을 보낸다.",
                example = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34")
        @NotBlank @Size(min = 36, max = 36)
        String requestKey,

        @Schema(description = "화면이 사용자에게 안내한 예상 요금(원). 서버 재계산 결과와 다르면 "
                + "주문을 만들지 않는다. 이 값이 결제 금액으로 쓰이지는 않는다.", example = "6400")
        @NotNull @Positive
        Long estimatedFare,

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
