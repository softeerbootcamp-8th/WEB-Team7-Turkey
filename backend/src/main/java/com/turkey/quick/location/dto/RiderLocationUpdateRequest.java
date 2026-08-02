package com.turkey.quick.location.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

public record RiderLocationUpdateRequest(
        @NotNull
        Long deliveryId,

        @NotNull @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,

        @NotNull @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude,

        @NotNull
        Instant measuredAt,

        @PositiveOrZero
        BigDecimal accuracyMeters
) {
    public LocationPayload toLocationPayload() {
        return new LocationPayload(latitude, longitude, measuredAt, accuracyMeters);
    }
}
