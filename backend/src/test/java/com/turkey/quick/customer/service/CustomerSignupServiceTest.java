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
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    private PointWalletRepository pointWalletRepository;
    private InMemoryVerificationCodeStore verificationCodeStore;
    private CustomerSignupService customerSignupService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        termRepository = mock(TermRepository.class);
        memberTermAgreementRepository = mock(MemberTermAgreementRepository.class);
        pointWalletRepository = mock(PointWalletRepository.class);
        verificationCodeStore = new InMemoryVerificationCodeStore();
        customerSignupService = new CustomerSignupService(
                memberRepository, termRepository, memberTermAgreementRepository,
                pointWalletRepository, verificationCodeStore);

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
    @DisplayName("정상 가입하면 비밀번호를 해시해 저장하고 결과를 반환한다")
    void shouldHashPasswordSaveMemberAndReturnResult() {
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
    @DisplayName("정상 가입하면 포인트 지갑을 생성한다")
    void shouldCreatePointWalletOnSignup() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());

        customerSignupService.signup(request(List.of()));

        ArgumentCaptor<PointWallet> captor = ArgumentCaptor.forClass(PointWallet.class);
        verify(pointWalletRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isZero();
    }

    @Test
    @DisplayName("아이디 또는 휴대전화 중복으로 가입에 실패하면 포인트 지갑을 만들지 않는다")
    void shouldNotCreatePointWalletWhenDuplicateMemberDataRejectsSignup() {
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class);

        verify(pointWalletRepository, never()).save(any());
    }

    @Test
    @DisplayName("DB 유니크 제약 위반으로 가입에 실패하면 포인트 지갑을 만들지 않는다")
    void shouldNotCreatePointWalletWhenDatabaseUniqueConstraintRejectsSignup() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());
        when(memberRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_member_login_id"));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class);

        verify(pointWalletRepository, never()).save(any());
    }

    @Test
    @DisplayName("정상 가입하면 동의한 약관마다 동의 이력을 저장한다")
    void shouldSaveAgreementHistoryForEveryAgreedTerm() {
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
    @DisplayName("비밀번호와 비밀번호확인이 다르면 거부한다")
    void shouldRejectWhenPasswordConfirmationDoesNotMatch() {
        var mismatched = new CustomerSignupRequest(
                LOGIN_ID, "p@ssw0rd", "different", "홍길동", PHONE_NUMBER, TOKEN, List.of());

        assertThatThrownBy(() -> customerSignupService.signup(mismatched))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 사용중인 아이디면 거부한다")
    void shouldRejectAlreadyUsedLoginId() {
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("이미 가입된 휴대전화 번호면 거부한다")
    void shouldRejectAlreadyRegisteredPhoneNumber() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("인증 토큰이 없거나 만료됐으면 거부한다")
    void shouldRejectMissingOrExpiredVerificationToken() {
        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("인증 토큰은 한 번만 쓸 수 있다")
    void shouldConsumeVerificationTokenOnlyOnce() {
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
    @DisplayName("계정찾기 목적으로 발급된 토큰으로는 가입할 수 없다")
    void shouldRejectTokenIssuedForAccountRecovery() {
        issueVerifiedToken(VerificationPurpose.FIND_ID, PHONE_NUMBER);

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("인증된 휴대전화 번호와 다르면 거부한다")
    void shouldRejectDifferentVerifiedPhoneNumber() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, "01099998888");

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("필수 약관에 동의하지 않아도 가입할 수 있다(#546, 백엔드 필수 약관 검증 제거)")
    void shouldAllowSignupWithoutAgreeingToRequiredTerm() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        CustomerSignupResult result = customerSignupService.signup(request(List.of()));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
    }

    @Test
    @DisplayName("아직 발효되지 않은 필수 약관은 동의하지 않아도 가입할 수 있다")
    void shouldAllowSignupWithoutAgreementToNotYetEffectiveRequiredTerm() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        Term notYetEffective = term(1L, true, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(notYetEffective));

        CustomerSignupResult result = customerSignupService.signup(request(List.of()));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
    }

    @Test
    @DisplayName("이미 종료된 필수 약관은 동의하지 않아도 가입할 수 있다")
    void shouldAllowSignupWithoutAgreementToExpiredRequiredTerm() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        LocalDateTime yesterday = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        Term expired = term(1L, true, yesterday.minusDays(1), yesterday);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(expired));

        CustomerSignupResult result = customerSignupService.signup(request(List.of()));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
    }

    @Test
    @DisplayName("약관 검증에 실패하면 인증 토큰을 소비하지 않는다")
    void shouldNotConsumeVerificationTokenWhenTermValidationFails() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of(1L, 999L))))
                .isInstanceOf(BusinessException.class);

        // 토큰이 아직 살아 있어야 사용자가 약관만 고쳐서 재시도할 수 있다.
        assertThat(verificationCodeStore.verifiedTokenValue(TOKEN)).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 약관 ID로 요청하면 거부한다")
    void shouldRejectUnknownTermId() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> customerSignupService.signup(request(List.of(1L, 999L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("사전 중복 확인을 통과해도 DB 유니크 제약을 어기면 409로 변환한다")
    void shouldTranslateDatabaseUniqueConstraintViolationToConflict() {
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
