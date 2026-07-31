package com.turkey.quick.rider.auth;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 라이더 전용 API의 세션 인증(이슈 #50). {@code CustomerSessionInterceptor}(#27/#29)와
 * 판단 흐름·실패 응답 정책이 동일하고, 역할이 RIDER라는 것과 회원 조회에 더해 라이더 프로필
 * (현재 운행 상태)까지 함께 조회해 {@link AuthenticatedRider}로 담아 넘긴다는 점만 다르다.
 *
 * 인증 실패 시 만료 쿠키를 함께 응답하는 것(#29에서 고객 쪽에 추가된 처리)을 이 인터셉터는
 * 처음부터 포함한다 — 나중에 라이더 세션 만료 이슈(#52)에서 똑같이 다시 추가할 이유가 없다.
 */
public class RiderSessionInterceptor implements HandlerInterceptor {

    public static final String CURRENT_RIDER_ATTRIBUTE = "currentRider";
    private static final String AUTH_FAILURE_MESSAGE = "로그인이 필요합니다.";

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final boolean cookieSecure;

    public RiderSessionInterceptor(SessionStore sessionStore, MemberRepository memberRepository,
                                    RiderProfileRepository riderProfileRepository, boolean cookieSecure) {
        this.sessionStore = sessionStore;
        this.memberRepository = memberRepository;
        this.riderProfileRepository = riderProfileRepository;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = SessionCookie.extractSessionId(request);
        if (sessionId == null) {
            throw authFailure(response);
        }

        Long memberId = sessionStore.findMemberId(sessionId).orElse(null);
        if (memberId == null) {
            throw authFailure(response);
        }

        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null) {
            throw authFailure(response);
        }

        if (member.getRole() != MemberRole.RIDER || !member.isActive()) {
            throw authFailure(response);
        }

        RiderProfile profile = riderProfileRepository.findById(memberId).orElse(null);
        if (profile == null) {
            throw authFailure(response);
        }

        request.setAttribute(CURRENT_RIDER_ATTRIBUTE, AuthenticatedRider.from(member, profile));
        return true;
    }

    private BusinessException authFailure(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.expired(cookieSecure).toString());
        return new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE);
    }
}
