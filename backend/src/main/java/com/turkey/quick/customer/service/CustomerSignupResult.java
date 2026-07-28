package com.turkey.quick.customer.service;

import com.turkey.quick.member.domain.Member;
import java.time.LocalDateTime;

public record CustomerSignupResult(Long memberId, String loginId, String name, LocalDateTime createdAt) {

    public static CustomerSignupResult from(Member member) {
        return new CustomerSignupResult(member.getId(), member.getLoginId(), member.getName(), member.getCreatedAt());
    }
}
