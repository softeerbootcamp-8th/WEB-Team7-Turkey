package com.turkey.quick.rider.auth;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.auth.SessionStore.SessionInfo;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
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
    private final RiderProfileRepository riderProfileRepository;
    private final boolean cookieSecure;

    public RiderSessionInterceptor(SessionStore sessionStore,
                                    RiderProfileRepository riderProfileRepository, boolean cookieSecure) {
        this.sessionStore = sessionStore;
        this.riderProfileRepository = riderProfileRepository;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = SessionCookie.extractSessionId(request);
        if (sessionId == null) {
            throw authFailure(response);
        }

        SessionInfo session = sessionStore.find(sessionId).orElse(null);
        if (session == null) {
            throw authFailure(response);
        }

        if (Duration.between(session.createdAt(), Instant.now()).compareTo(SessionStore.ABSOLUTE_TTL) > 0) {
            sessionStore.delete(sessionId);
            throw authFailure(response);
        }

        RiderProfile profile = riderProfileRepository.findWithMemberById(session.memberId()).orElse(null);
        if (profile == null) {
            throw authFailure(response);
        }

        Member member = profile.getMember(); // join fetch로 이미 로딩됨, 추가 쿼리 없음
        if (member.getRole() != MemberRole.RIDER || !member.isActive()) {
            throw authFailure(response);
        }

        slideSession(sessionId);
        request.setAttribute(CURRENT_RIDER_ATTRIBUTE, AuthenticatedRider.from(member, profile));
        return true;
    }

    /**
     * 인증을 통과한 요청마다 세션 TTL을 다시 건다(슬라이딩 갱신, #439). 고객 인터셉터와 같은 처리다
     * — 이 저장소는 두 인터셉터를 공용 추출하지 않기로 했으므로(CLAUDE.md) 같은 줄을 각자 둔다.
     *
     * <p>BUSY 라이더에게 이게 핵심이다. 배송 중 5초 주기 위치 전송이 그대로 활동 신호가 되어 배송
     * 도중 세션이 만료되지 않는다 — 만료되면 위치 POST와 배송 완료가 함께 401로 막혔다(#439).
     * 쿠키는 다시 내리지 않는다 — Max-Age가 이 TTL과 더 이상 묶여 있지 않아({@link SessionCookie})
     * 클라이언트가 먼저 죽는 문제 자체가 안 생긴다.
     */
    private void slideSession(String sessionId) {
        sessionStore.extend(sessionId, SessionStore.DEFAULT_TTL);
    }

    private BusinessException authFailure(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.expired(cookieSecure).toString());
        return new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE);
    }
}
