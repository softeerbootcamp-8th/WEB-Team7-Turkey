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
    void 올바른_아이디와_비밀번호로_로그인하면_세션을_생성하고_회원정보를_반환한다() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        CustomerLoginResult result = customerLoginService.login(LOGIN_ID, RAW_PASSWORD);

        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
        assertThat(result.name()).isEqualTo("홍길동");
    }

    @Test
    void 로그인_성공_시_세션에_회원ID가_저장된다() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        CustomerLoginResult result = customerLoginService.login(LOGIN_ID, RAW_PASSWORD);

        assertThat(sessionStore.get(result.sessionId())).containsKey("memberId");
    }

    @Test
    void 존재하지_않는_아이디로_로그인하면_401을_반환한다() {
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_계정으로_고객_로그인을_시도하면_401을_반환한다() {
        Member rider = Member.create(LOGIN_ID, passwordEncoder.encode(RAW_PASSWORD), "라이더", "01099998888", MemberRole.RIDER);
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(rider));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 비밀번호가_틀리면_401을_반환한다() {
        Member member = customer();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, "wrong-password"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 탈퇴한_계정으로_로그인하면_401을_반환한다() {
        Member member = customer();
        member.withdraw();
        when(memberRepository.findByLoginId(LOGIN_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> customerLoginService.login(LOGIN_ID, RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 실패_사유와_무관하게_동일한_메시지를_반환한다() {
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
