package com.turkey.quick.customer.auth;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 고객 전용 API의 세션 인증(이슈 #27). SESSION_ID 쿠키 → Redis 세션 → 회원 조회 → 역할·상태
 * 확인까지 통과하면 request attribute에 {@link AuthenticatedCustomer}를 담아 컨트롤러가
 * {@code @RequestAttribute}로 꺼내 쓸 수 있게 한다.
 *
 * 예외(BusinessException)는 preHandle 안에서 던진다 — DispatcherServlet.doDispatch()가
 * 인터셉터 실행을 감싸는 try 블록 안에 있어, 여기서 던진 예외도 GlobalExceptionHandler
 * (@RestControllerAdvice)가 그대로 잡아 기존 ApiResponse 에러 포맷으로 응답한다.
 * (반대로 Filter에서 던지면 DispatcherServlet 바깥이라 GlobalExceptionHandler가 못 잡는다.)
 *
 * 실패 사유(쿠키 없음/세션 없음/회원 없음/역할 불일치/비활성 계정)를 구분하지 않고 전부 동일한
 * 401 메시지로 응답한다 — #26 로그인과 같은 이유(계정·세션 상태를 구체적으로 노출하지 않는다).
 */
public class CustomerSessionInterceptor implements HandlerInterceptor {

    public static final String CURRENT_CUSTOMER_ATTRIBUTE = "currentCustomer";
    private static final String AUTH_FAILURE_MESSAGE = "로그인이 필요합니다.";

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;

    public CustomerSessionInterceptor(SessionStore sessionStore, MemberRepository memberRepository) {
        this.sessionStore = sessionStore;
        this.memberRepository = memberRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE);
        }

        Long memberId = sessionStore.findMemberId(sessionId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE));

        if (member.getRole() != MemberRole.CUSTOMER || !member.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE);
        }

        request.setAttribute(CURRENT_CUSTOMER_ATTRIBUTE, AuthenticatedCustomer.from(member));
        return true;
    }

    private String extractSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SessionCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
