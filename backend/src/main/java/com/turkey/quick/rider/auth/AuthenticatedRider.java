package com.turkey.quick.rider.auth;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;

/**
 * 세션 인증을 통과한 라이더 정보. {@code AuthenticatedCustomer}(#27)와 같은 이유로
 * 인터셉터가 JPA 엔티티를 그대로 request attribute에 담지 않고 이 레코드로 변환해 넘긴다.
 */
public record AuthenticatedRider(Long memberId, String loginId, String name, OperatingStatus operatingStatus) {

    public static AuthenticatedRider from(Member member, RiderProfile profile) {
        return new AuthenticatedRider(member.getId(), member.getLoginId(), member.getName(), profile.getOperatingStatus());
    }
}
