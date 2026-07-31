package com.turkey.quick.member.service;

public record LoginIdAvailabilityResult(boolean available, String reason) {

    public static LoginIdAvailabilityResult ofAvailable() {
        return new LoginIdAvailabilityResult(true, null);
    }

    public static LoginIdAvailabilityResult ofUnavailable(String reason) {
        return new LoginIdAvailabilityResult(false, reason);
    }
}
