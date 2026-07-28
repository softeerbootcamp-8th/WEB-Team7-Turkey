package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 배송요청 취소.
 *
 * 취소할 상태를 받지 않는다 — 취소는 "현재 WAITING 인가"로만 판정하는 행위다.
 * 배차 이후 취소는 MVP 범위 밖이므로, ASSIGNED 이상이면 서버가 거부한다.
 */
@Schema(description = "배송요청 취소 요청")
public record DeliveryCancelRequest(

        @Schema(description = "취소 사유(선택)", example = "주소를 잘못 입력했습니다")
        @Size(max = 255)
        String reason
) {
}
