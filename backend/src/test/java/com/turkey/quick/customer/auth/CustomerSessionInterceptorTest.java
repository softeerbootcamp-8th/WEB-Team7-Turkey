package com.turkey.quick.customer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.auth.SessionCookie;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
        interceptor = new CustomerSessionInterceptor(sessionStore, memberRepository);
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
    void 유효한_세션이면_통과하고_인증된_고객을_request_attribute에_담는다() {
        String sessionId = "valid-session";
        sessionStore.create(sessionId, MEMBER_ID, "CUSTOMER", Duration.ofHours(2));
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
    void 세션_쿠키가_없으면_401을_던진다() {
        MockHttpServletRequest request = requestWithCookie(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 존재하지_않는_세션이면_401을_던진다() {
        MockHttpServletRequest request = requestWithCookie("no-such-session");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 세션은_있지만_회원이_없으면_401을_던진다() {
        String sessionId = "orphan-session";
        sessionStore.create(sessionId, MEMBER_ID, "CUSTOMER", Duration.ofHours(2));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_세션으로_고객_API에_접근하면_401을_던진다() {
        String sessionId = "rider-session";
        sessionStore.create(sessionId, MEMBER_ID, "RIDER", Duration.ofHours(2));
        Member rider = Member.create("rider01", "encoded", "라이더", "01099998888", MemberRole.RIDER);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(rider));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 세션_생성_이후_탈퇴한_계정이면_401을_던진다() {
        String sessionId = "withdrawn-session";
        sessionStore.create(sessionId, MEMBER_ID, "CUSTOMER", Duration.ofHours(2));
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
