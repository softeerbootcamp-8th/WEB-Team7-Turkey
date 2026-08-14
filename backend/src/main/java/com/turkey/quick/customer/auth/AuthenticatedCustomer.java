package com.turkey.quick.customer.auth;

import com.turkey.quick.member.domain.Member;

/**
 * 세션 인증을 통과한 고객 정보. 인터셉터가 JPA 엔티티(Member)를 그대로 request attribute에
 * 담지 않고 이 레코드로 변환해 넘긴다 — 컨트롤러가 엔티티에 직접 의존하지 않도록 하기 위함.
 */
public record AuthenticatedCustomer(Long memberId, String loginId, String name, String phoneNumber) {

    public static AuthenticatedCustomer from(Member member) {
        return new AuthenticatedCustomer(member.getId(), member.getLoginId(), member.getName(), member.getPhoneNumber());
    }
}
