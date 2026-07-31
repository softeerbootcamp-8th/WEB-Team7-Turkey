package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 주문에 스냅샷으로 저장된 주소 응답.
 * 주문 시점 값이므로, 회원의 현재 주소가 바뀌어도 과거 주문 응답은 그대로다.
 */
@Schema(description = "주소(주문 시점 스냅샷)")
public record AddressResponse(

        @Schema(description = "도로명 주소", example = "서울 강남구 테헤란로 152")
        String roadAddress,

        @Schema(description = "상세 주소", example = "5층 프론트데스크")
        String detailAddress,

        @Schema(description = "우편번호", example = "06236")
        String postalCode,

        @Schema(description = "위도", example = "37.5006")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0366")
        BigDecimal longitude
) {
}
