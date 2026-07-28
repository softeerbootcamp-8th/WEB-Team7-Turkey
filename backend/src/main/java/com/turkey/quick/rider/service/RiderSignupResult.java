package com.turkey.quick.rider.service;

import com.turkey.quick.member.domain.Member;
import java.time.LocalDateTime;

public record RiderSignupResult(Long memberId, String loginId, String name, LocalDateTime createdAt) {

    public static RiderSignupResult from(Member member) {
        return new RiderSignupResult(member.getId(), member.getLoginId(), member.getName(), member.getCreatedAt());
    }
}
