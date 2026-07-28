package com.turkey.quick.rider.service;

import com.turkey.quick.rider.domain.OperatingStatus;
import java.time.Duration;

public record RiderLoginResult(
        String sessionId, Duration sessionTtl, Long memberId, String loginId, String name,
        OperatingStatus operatingStatus) {
}
