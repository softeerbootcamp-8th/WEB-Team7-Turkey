package com.turkey.quick.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberTermAgreement;
import com.turkey.quick.member.domain.Term;
import com.turkey.quick.member.domain.TermTargetRole;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.member.repository.MemberTermAgreementRepository;
import com.turkey.quick.member.repository.TermRepository;
import com.turkey.quick.member.service.InMemoryVerificationCodeStore;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CustomerSignupServiceTest {

    private static final String LOGIN_ID = "quick_user01";
    private static final String PHONE_NUMBER = "01012345678";
    private static final String TOKEN = "verified-token";

    private MemberRepository memberRepository;
    private TermRepository termRepository;
    private MemberTermAgreementRepository memberTermAgreementRepository;
    private InMemoryVerificationCodeStore verificationCodeStore;
    private CustomerSignupService customerSignupService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        termRepository = mock(TermRepository.class);
        memberTermAgreementRepository = mock(MemberTermAgreementRepository.class);
        verificationCodeStore = new InMemoryVerificationCodeStore();
        customerSignupService = new CustomerSignupService(
                memberRepository, termRepository, memberTermAgreementRepository, verificationCodeStore);

        when(memberRepository.existsByLoginId(any())).thenReturn(false);
        when(memberRepository.existsByPhoneNumber(any())).thenReturn(false);
        when(memberRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CustomerSignupRequest request(List<Long> agreedTermIds) {
        return new CustomerSignupRequest(
                LOGIN_ID, "p@ssw0rd", "p@ssw0rd", "홍길동", PHONE_NUMBER, TOKEN, agreedTermIds);
    }

    private void issueVerifiedToken(VerificationPurpose purpose, String phoneNumber) {
        verificationCodeStore.saveVerifiedToken(TOKEN, purpose, phoneNumber, Duration.ofMinutes(10));
    }

    /** Term은 JPA로 영속돼야 id가 생기므로, 순수 단위 테스트에서는 리플렉션으로 id를 채운 픽스처를 쓴다. */
    private Term term(Long id, boolean required) {
        return term(id, required, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    private Term term(Long id, boolean required, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        Term term = Term.create("SERVICE", TermTargetRole.COMMON, "약관", "본문", "1.0",
                required, effectiveFrom, effectiveTo);
        try {
            Field field = Term.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(term, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return term;
    }

    @Test
    void 정상_가입하면_비밀번호를_해시해_저장하고_결과를_반환한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        CustomerSignupResult result = customerSignupService.signup(request(List.of(1L)));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
        assertThat(result.name()).isEqualTo("홍길동");

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("p@ssw0rd");
        assertThat(new BCryptPasswordEncoder().matches("p@ssw0rd", saved.getPasswordHash())).isTrue();
    }

    @Test
    void 정상_가입하면_동의한_약관마다_동의_이력을_저장한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any()))
                .thenReturn(List.of(term(1L, true), term(2L, false)));

        customerSignupService.signup(request(List.of(1L, 2L)));

        ArgumentCaptor<List<MemberTermAgreement>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberTermAgreementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allMatch(MemberTermAgreement::isAgreed);
    }

    @Test
    void 비밀번호와_비밀번호확인이_다르면_거부한다() {
        var mismatched = new CustomerSignupRequest(
                LOGIN_ID, "p@ssw0rd", "different", "홍길동", PHONE_NUMBER, TOKEN, List.of());

        assertThatThrownBy(() -> customerSignupService.signup(mismatched))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void 이미_사용중인_아이디면_거부한다() {
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 이미_가입된_휴대전화_번호면_거부한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 인증_토큰이_없거나_만료됐으면_거부한다() {
        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 인증_토큰은_한_번만_쓸_수_있다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());
        customerSignupService.signup(request(List.of()));
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(false);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 계정찾기_목적으로_발급된_토큰으로는_가입할_수_없다() {
        issueVerifiedToken(VerificationPurpose.FIND_ID, PHONE_NUMBER);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 인증된_휴대전화_번호와_다르면_거부한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, "01099998888");

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 필수_약관에_동의하지_않으면_거부한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void 아직_발효되지_않은_필수_약관은_동의하지_않아도_가입할_수_있다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        Term notYetEffective = term(1L, true, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(notYetEffective));

        CustomerSignupResult result = customerSignupService.signup(request(List.of()));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
    }

    @Test
    void 이미_종료된_필수_약관은_동의하지_않아도_가입할_수_있다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        LocalDateTime yesterday = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        Term expired = term(1L, true, yesterday.minusDays(1), yesterday);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(expired));

        CustomerSignupResult result = customerSignupService.signup(request(List.of()));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
    }

    @Test
    void 약관_검증에_실패하면_인증_토큰을_소비하지_않는다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class);

        // 토큰이 아직 살아 있어야 사용자가 약관만 고쳐서 재시도할 수 있다.
        assertThat(verificationCodeStore.verifiedTokenValue(TOKEN)).isNotNull();
    }

    @Test
    void 존재하지_않는_약관_ID로_요청하면_거부한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of(1L, 999L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 사전_중복_확인을_통과해도_DB_유니크_제약을_어기면_409로_변환한다() {
        // 존재 확인과 저장 사이의 경쟁: existsByLoginId는 통과했지만 그 사이 다른 요청이 먼저 가입을 끝낸 경우.
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());
        when(memberRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_member_login_id"));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
