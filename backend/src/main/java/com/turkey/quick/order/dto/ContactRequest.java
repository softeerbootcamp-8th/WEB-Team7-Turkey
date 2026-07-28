package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 보내는 사람 / 받는 사람 연락처 입력. {@link com.turkey.quick.order.domain.Contact} 와 1:1 이다.
 * 받는 사람은 회원이 아닐 수 있으므로 회원 식별자가 아니라 이름·전화번호를 그대로 받는다.
 */
@Schema(description = "연락처 입력(이름 + 전화번호)")
public record ContactRequest(

        @Schema(description = "이름", example = "김고객")
        @NotBlank @Size(max = 50)
        String name,

        @Schema(description = "전화번호", example = "010-1234-5678")
        @NotBlank @Size(max = 20)
        String phoneNumber
) {
}
