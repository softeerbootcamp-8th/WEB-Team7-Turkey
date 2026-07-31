package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 진행 중 배송의 단계 전이 요청. 목표 상태가 아니라 수행한 행위를 받는다. */
@Schema(description = "배송 단계 전이 요청")
public record RiderDeliveryTransitionRequest(

        @Schema(description = "수행할 행위")
        @NotNull
        RiderDeliveryAction action
) {
}
