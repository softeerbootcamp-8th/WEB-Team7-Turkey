package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * CustomerLoginServiceTest(#26)와 같은 시나리오는 반복하지 않고, 라이더 로그인에서만 갈리는
 * 부분(역할 RIDER, 응답에 현재 운행 상태 포함)에 집중한다.
 */
class RiderLoginServiceTest {

    private static final String LOGIN_ID = "quick_rider01";
    private static final String RAW_PASSWORD = "p@ssw0rd";

    private MemberRepository memberRepository;
    private RiderProfileRepository riderProfileRepository;
    private InMemorySessionStore sessionStore;
    private RiderLoginService riderLoginService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        riderProfileRepository = mock(RiderProfileRepository.class);
        sessionStore = new InMemorySessionStore();
        riderLoginService = new RiderLoginService(memberRepository, riderProfileRepository, sessionStore);
    }

    private Member rider() {
        return Member.create(LOGIN_ID, passwordEncoder.encode(RAW_PASSWORD), "홍길동", "01012345678", MemberRole.RIDER);
    }

    @Test
    @DisplayName("올바른 아이디와 비밀번호로 로그인하면 세션을 생성하고 현재 운행 상태를 반환한다")
    void shouldCreateSessionAndReturnOperatingStatusForValidCredentials() {
        Member member = rider();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));
        when(riderProfileRepository.findById(member.getId()))
                .thenReturn(Optional.of(RiderProfile.create(member)));

        RiderLoginResult result = riderLoginService.login(LOGIN_ID, RAW_PASSWORD);

        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(sessionStore.get(result.sessionId())).containsKey("memberId");
    }

    @Test
    @DisplayName("고객 계정으로 라이더 로그인을 시도하면 401을 반환한다")
    void shouldReturnUnauthorizedForCustomerAccount() {
        Member customer = Member.create(LOGIN_ID, passwordEncoder.encode(RAW_PASSWORD), "고객", "01099998888", MemberRole.CUSTOMER);
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> riderLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("존재하지 않는 아이디로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForUnknownLoginId() {
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> riderLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401을 반환한다")
    void shouldReturnUnauthorizedForWrongPassword() {
        Member member = rider();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> riderLoginService.login(LOGIN_ID, "wrong-password"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("탈퇴한 계정으로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForWithdrawnAccount() {
        Member member = rider();
        member.withdraw();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> riderLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
