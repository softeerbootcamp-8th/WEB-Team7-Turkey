package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 픽업지·도착지 주소 입력. {@link com.turkey.quick.order.domain.Address} 의 값 구성과 1:1 로 맞춘다.
 *
 * 좌표 범위는 Address 생성자와 DB CHECK 가 다시 검증한다. 여기서 같은 조건을 다는 것은
 * 잘못된 입력을 컨트롤러 경계에서 400 으로 끊어 도메인까지 내려보내지 않기 위해서다.
 */
@Schema(description = "주소 입력(도로명 + 상세 + 우편번호 + 좌표)")
public record AddressRequest(

        @Schema(description = "도로명 주소", example = "서울 강남구 테헤란로 152")
        @NotBlank @Size(max = 255)
        String roadAddress,

        @Schema(description = "상세 주소(선택). 단독주택 등 상세 주소가 없으면 생략한다.", example = "5층 프론트데스크")
        @Size(max = 255)
        String detailAddress,

        @Schema(description = "우편번호", example = "06236")
        @NotBlank @Size(max = 10)
        String postalCode,

        @Schema(description = "위도", example = "37.5006")
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0366")
        @NotNull @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude
) {
}
