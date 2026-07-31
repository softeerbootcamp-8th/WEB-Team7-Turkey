package com.turkey.quick.customer.service;

import java.time.Duration;

public record CustomerLoginResult(
        String sessionId,
        Duration sessionTtl,
        Long memberId,
        String loginId,
        String name
) {
}
