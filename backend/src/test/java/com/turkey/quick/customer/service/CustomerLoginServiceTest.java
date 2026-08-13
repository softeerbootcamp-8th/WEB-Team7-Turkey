package com.turkey.quick.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CustomerLoginServiceTest {

    private static final String LOGIN_ID = "quick_user01";
    private static final String RAW_PASSWORD = "p@ssw0rd";

    private MemberRepository memberRepository;
    private InMemorySessionStore sessionStore;
    private CustomerLoginService customerLoginService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        sessionStore = new InMemorySessionStore();
        customerLoginService = new CustomerLoginService(memberRepository, sessionStore);
    }

    private Member customer() {
        return Member.create(LOGIN_ID, passwordEncoder.encode(RAW_PASSWORD), "홍길동", "01012345678", MemberRole.CUSTOMER);
    }

    @Test
    @DisplayName("올바른 아이디와 비밀번호로 로그인하면 세션을 생성하고 회원정보를 반환한다")
    void shouldCreateSessionAndReturnMemberForValidCredentials() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        CustomerLoginResult result = customerLoginService.login(LOGIN_ID, RAW_PASSWORD);

        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
        assertThat(result.name()).isEqualTo("홍길동");
    }

    @Test
    // 세션 해시에서 role 을 없앴으므로(#439) 역할 저장은 더 이상 검증 대상이 아니다.
    @DisplayName("로그인 성공 시 세션에 회원ID가 저장된다")
    void shouldStoreMemberIdInSessionAfterLogin() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        CustomerLoginResult result = customerLoginService.login(LOGIN_ID, RAW_PASSWORD);

        assertThat(sessionStore.get(result.sessionId())).containsKey("memberId");
    }

    @Test
    @DisplayName("존재하지 않는 아이디로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForUnknownLoginId() {
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("라이더 계정으로 고객 로그인을 시도하면 401을 반환한다")
    void shouldReturnUnauthorizedForRiderAccount() {
        Member rider = Member.create(LOGIN_ID, passwordEncoder.encode(RAW_PASSWORD), "라이더", "01099998888", MemberRole.RIDER);
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(rider));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401을 반환한다")
    void shouldReturnUnauthorizedForWrongPassword() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, "wrong-password"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("탈퇴한 계정으로 로그인하면 401을 반환한다")
    void shouldReturnUnauthorizedForWithdrawnAccount() {
        Member member = customer();
        member.withdraw();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("실패 사유와 무관하게 동일한 메시지를 반환한다")
    void shouldReturnSameMessageRegardlessOfFailureReason() {
        when(memberRepository.findByLoginId("no_such_id")).thenReturn(Optional.empty());
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        String unknownIdMessage = catchMessage(() -> customerLoginService.login("no_such_id", RAW_PASSWORD));
        String wrongPasswordMessage = catchMessage(() -> customerLoginService.login(LOGIN_ID, "wrong-password"));

        assertThat(unknownIdMessage).isEqualTo(wrongPasswordMessage);
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("예외가 발생해야 한다");
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }
}
