package com.turkey.quick.location.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record LocationPayload(
        BigDecimal latitude,
        BigDecimal longitude,
        Instant measuredAt,
        BigDecimal accuracyMeters
) {}
