package com.turkey.quick.customer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CustomerSessionInterceptorTest {

    private static final Long MEMBER_ID = 1L;

    private InMemorySessionStore sessionStore;
    private MemberRepository memberRepository;
    private CustomerSessionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionStore();
        memberRepository = mock(MemberRepository.class);
        interceptor = new CustomerSessionInterceptor(sessionStore, memberRepository, true);
    }

    private Member customer() {
        return Member.create("session_user01", "encoded", "홍길동", "01012345678", MemberRole.CUSTOMER);
    }

    private MockHttpServletRequest requestWithCookie(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customer/session");
        if (sessionId != null) {
            request.setCookies(new Cookie(SessionCookie.NAME, sessionId));
        }
        return request;
    }

    @Test
    @DisplayName("유효한 세션이면 통과하고 인증된 고객을 request attribute에 담는다")
    void shouldAuthenticateCustomerAndContinueForValidSession() {
        String sessionId = "valid-session";
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        Member member = customer();
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        AuthenticatedCustomer customer = (AuthenticatedCustomer)
                request.getAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE);
        assertThat(customer.loginId()).isEqualTo("session_user01");
        assertThat(customer.name()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("인증을 통과한 요청은 세션 TTL만 연장하고 쿠키는 다시 내리지 않는다")
    void shouldExtendSessionTtlWithoutReissuingCookie() {
        String sessionId = "sliding-session";
        sessionStore.create(sessionId, MEMBER_ID, SessionStore.DEFAULT_TTL);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(customer()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(requestWithCookie(sessionId), response, new Object());

        assertThat(sessionStore.extendCount(sessionId)).isEqualTo(1);
        // 쿠키 Max-Age는 세션 TTL과 더 이상 묶여 있지 않다 — 로그인 시점에만 발급한다.
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("세션 쿠키가 없으면 401을 던진다")
    void shouldThrowUnauthorizedWithoutSessionCookie() {
        MockHttpServletRequest request = requestWithCookie(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("인증에 실패하면 응답에 만료 쿠키를 함께 보낸다")
    void shouldSendExpiredCookieWhenAuthenticationFails() {
        MockHttpServletRequest request = requestWithCookie("no-such-session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BusinessException.class);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("SESSION_ID=");
        assertThat(setCookie).containsIgnoringCase("Max-Age=0");
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 401을 던진다")
    void shouldThrowUnauthorizedForUnknownSession() {
        MockHttpServletRequest request = requestWithCookie("no-such-session");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("세션은 있지만 회원이 없으면 401을 던진다")
    void shouldThrowUnauthorizedWhenSessionMemberDoesNotExist() {
        String sessionId = "orphan-session";
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("라이더 세션으로 고객 API에 접근하면 401을 던진다")
    void shouldThrowUnauthorizedWhenRiderSessionAccessesCustomerApi() {
        String sessionId = "rider-session";
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        Member rider = Member.create("rider01", "encoded", "라이더", "01099998888", MemberRole.RIDER);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(rider));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("세션 생성 이후 탈퇴한 계정이면 401을 던진다")
    void shouldThrowUnauthorizedForAccountWithdrawnAfterSessionCreation() {
        String sessionId = "withdrawn-session";
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        Member member = customer();
        member.withdraw();
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
