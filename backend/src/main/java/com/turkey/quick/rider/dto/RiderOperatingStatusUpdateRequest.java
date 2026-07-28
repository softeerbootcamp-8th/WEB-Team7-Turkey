package com.turkey.quick.rider.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 운행 상태 변경 요청. 목표 상태가 아니라 수행할 행위를 받는다. */
@Schema(description = "운행 상태 변경 요청")
public record RiderOperatingStatusUpdateRequest(

        @Schema(description = "수행할 행위")
        @NotNull
        RiderOperatingAction action
) {
}
