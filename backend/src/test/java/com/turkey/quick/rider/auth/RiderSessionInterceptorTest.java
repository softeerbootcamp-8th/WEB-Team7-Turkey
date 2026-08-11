package com.turkey.quick.rider.auth;

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
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * CustomerSessionInterceptorTest(#27/#29)와 같은 시나리오는 반복하지 않고, 라이더 인터셉터에서만
 * 갈리는 부분(역할 RIDER, 라이더 프로필 조회, 운행 상태 포함)에 집중한다.
 */
class RiderSessionInterceptorTest {

    private static final Long MEMBER_ID = 1L;

    private InMemorySessionStore sessionStore;
    private MemberRepository memberRepository;
    private RiderProfileRepository riderProfileRepository;
    private RiderSessionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionStore();
        memberRepository = mock(MemberRepository.class);
        riderProfileRepository = mock(RiderProfileRepository.class);
        interceptor = new RiderSessionInterceptor(sessionStore, memberRepository, riderProfileRepository, true);
    }

    private Member rider() {
        return Member.create("session_rider01", "encoded", "홍길동", "01012345678", MemberRole.RIDER);
    }

    private MockHttpServletRequest requestWithCookie(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rider/session");
        if (sessionId != null) {
            request.setCookies(new Cookie(SessionCookie.NAME, sessionId));
        }
        return request;
    }

    @Test
    @DisplayName("유효한 세션이면 통과하고 운행 상태를 포함한 인증된 라이더를 request attribute에 담는다")
    void shouldAuthenticateRiderWithOperatingStatusAndContinue() {
        String sessionId = "valid-session";
        sessionStore.create(sessionId, MEMBER_ID, "RIDER", Duration.ofHours(2));
        Member member = rider();
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(riderProfileRepository.findById(MEMBER_ID)).thenReturn(Optional.of(RiderProfile.create(member)));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        AuthenticatedRider authenticated = (AuthenticatedRider)
                request.getAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE);
        assertThat(authenticated.loginId()).isEqualTo("session_rider01");
        assertThat(authenticated.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("고객 세션으로 라이더 API에 접근하면 401을 던진다")
    void shouldThrowUnauthorizedWhenCustomerSessionAccessesRiderApi() {
        String sessionId = "customer-session";
        sessionStore.create(sessionId, MEMBER_ID, "CUSTOMER", Duration.ofHours(2));
        Member customer = Member.create("session_customer01", "encoded", "고객", "01099998888", MemberRole.CUSTOMER);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(customer));
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("라이더 프로필이 없으면 401을 던진다")
    void shouldThrowUnauthorizedWithoutRiderProfile() {
        String sessionId = "no-profile-session";
        sessionStore.create(sessionId, MEMBER_ID, "RIDER", Duration.ofHours(2));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(rider()));
        when(riderProfileRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestWithCookie(sessionId);

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
}
