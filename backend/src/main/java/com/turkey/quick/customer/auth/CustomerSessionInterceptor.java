package com.turkey.quick.customer.auth;

import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 고객 전용 API의 세션 인증(이슈 #27) 및 만료 세션 처리(이슈 #29). SESSION_ID 쿠키 → Redis
 * 세션 → 회원 조회 → 역할·상태 확인까지 통과하면 request attribute에
 * {@link AuthenticatedCustomer}를 담아 컨트롤러가 {@code @RequestAttribute}로 꺼내 쓸 수
 * 있게 한다.
 *
 * 예외(BusinessException)는 preHandle 안에서 던진다 — DispatcherServlet.doDispatch()가
 * 인터셉터 실행을 감싸는 try 블록 안에 있어, 여기서 던진 예외도 GlobalExceptionHandler
 * (@RestControllerAdvice)가 그대로 잡아 기존 ApiResponse 에러 포맷으로 응답한다.
 * (반대로 Filter에서 던지면 DispatcherServlet 바깥이라 GlobalExceptionHandler가 못 잡는다.)
 *
 * 실패 사유(쿠키 없음/세션 없음(=만료 포함, Redis TTL로 자동 삭제됨)/회원 없음/역할 불일치/
 * 비활성 계정)를 구분하지 않고 전부 동일한 401 메시지로 응답한다 — #26 로그인과 같은 이유
 * (계정·세션 상태를 구체적으로 노출하지 않는다). 어떤 사유든 인증에 실패하면 응답에 만료
 * 쿠키(SESSION_ID; Max-Age=0)를 함께 실어 보내 클라이언트가 더 이상 쓸모없는 쿠키를 계속
 * 들고 있지 않게 한다(#29).
 */
public class CustomerSessionInterceptor implements HandlerInterceptor {

    public static final String CURRENT_CUSTOMER_ATTRIBUTE = "currentCustomer";
    private static final String AUTH_FAILURE_MESSAGE = "로그인이 필요합니다.";

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;
    private final boolean cookieSecure;

    public CustomerSessionInterceptor(SessionStore sessionStore, MemberRepository memberRepository, boolean cookieSecure) {
        this.sessionStore = sessionStore;
        this.memberRepository = memberRepository;
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

        if (member.getRole() != MemberRole.CUSTOMER || !member.isActive()) {
            throw authFailure(response);
        }

        request.setAttribute(CURRENT_CUSTOMER_ATTRIBUTE, AuthenticatedCustomer.from(member));
        return true;
    }

    private BusinessException authFailure(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.expired(cookieSecure).toString());
        return new BusinessException(HttpStatus.UNAUTHORIZED, AUTH_FAILURE_MESSAGE);
    }
}
