package com.turkey.quick.rider.auth;

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
    private RiderProfileRepository riderProfileRepository;
    private RiderSessionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionStore();
        riderProfileRepository = mock(RiderProfileRepository.class);
        interceptor = new RiderSessionInterceptor(sessionStore, riderProfileRepository, true);
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
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        Member member = rider();
        when(riderProfileRepository.findWithMemberById(MEMBER_ID)).thenReturn(Optional.of(RiderProfile.create(member)));
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
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        Member customer = Member.create("session_customer01", "encoded", "고객", "01099998888", MemberRole.CUSTOMER);
        when(riderProfileRepository.findWithMemberById(MEMBER_ID)).thenReturn(Optional.of(RiderProfile.create(customer)));
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
        sessionStore.create(sessionId, MEMBER_ID, Duration.ofHours(2));
        when(riderProfileRepository.findWithMemberById(MEMBER_ID)).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestWithCookie(sessionId);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("배송 수행중 라이더의 요청은 세션 TTL만 연장하고 쿠키는 다시 내리지 않는다")
    void shouldExtendSessionTtlForBusyRiderWithoutReissuingCookie() {
        // BUSY 라이더는 5초 주기로 위치를 POST한다. 그 요청이 세션을 연장하지 못하면 배송 도중
        // 세션이 만료돼 위치 전송과 배송 완료가 함께 401로 막힌다(#439).
        String sessionId = "busy-rider-session";
        sessionStore.create(sessionId, MEMBER_ID, SessionStore.DEFAULT_TTL);
        Member member = rider();
        RiderProfile profile = RiderProfile.create(member);
        profile.goOnline();
        profile.assign();
        when(riderProfileRepository.findWithMemberById(MEMBER_ID)).thenReturn(Optional.of(profile));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(requestWithCookie(sessionId), response, new Object());

        assertThat(sessionStore.extendCount(sessionId)).isEqualTo(1);
        // 쿠키 Max-Age는 세션 TTL과 더 이상 묶여 있지 않다 — 로그인 시점에만 발급한다.
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("인증에 실패하면 세션을 연장하지 않는다")
    void shouldNotExtendSessionWhenAuthenticationFails() {
        String sessionId = "customer-session";
        sessionStore.create(sessionId, MEMBER_ID, SessionStore.DEFAULT_TTL);
        Member customer = Member.create("session_customer01", "encoded", "고객", "01099998888", MemberRole.CUSTOMER);
        when(riderProfileRepository.findWithMemberById(MEMBER_ID)).thenReturn(Optional.of(RiderProfile.create(customer)));

        assertThatThrownBy(() ->
                interceptor.preHandle(requestWithCookie(sessionId), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class);

        assertThat(sessionStore.extendCount(sessionId)).isZero();
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
