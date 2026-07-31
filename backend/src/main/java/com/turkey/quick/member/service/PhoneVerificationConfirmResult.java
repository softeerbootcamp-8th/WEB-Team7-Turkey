package com.turkey.quick.member.service;

import java.time.LocalDateTime;

public record PhoneVerificationConfirmResult(String verificationToken, LocalDateTime expiresAt) {
}
