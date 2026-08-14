package com.turkey.quick.rider.service;

import com.turkey.quick.rider.domain.OperatingStatus;

public record RiderLoginResult(
        String sessionId, Long memberId, String loginId, String name,
        OperatingStatus operatingStatus) {
}
