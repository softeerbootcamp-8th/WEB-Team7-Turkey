package com.turkey.quick.customer.service;

public record CustomerLoginResult(
        String sessionId,
        Long memberId,
        String loginId,
        String name
) {
}
