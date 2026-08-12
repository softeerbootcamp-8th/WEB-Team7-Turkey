package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
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
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderSignupRequest;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * CustomerSignupServiceTest(#25)와 같은 시나리오는 반복하지 않고, 라이더 가입에서만 갈리는
 * 부분(역할 RIDER, 라이더 프로필 생성)에 집중한다.
 */
class RiderSignupServiceTest {

    private static final String LOGIN_ID = "quick_rider01";
    private static final String PHONE_NUMBER = "01012345678";
    private static final String TOKEN = "verified-token";

    private MemberRepository memberRepository;
    private RiderProfileRepository riderProfileRepository;
    private TermRepository termRepository;
    private MemberTermAgreementRepository memberTermAgreementRepository;
    private PointWalletRepository pointWalletRepository;
    private InMemoryVerificationCodeStore verificationCodeStore;
    private RiderSignupService riderSignupService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        riderProfileRepository = mock(RiderProfileRepository.class);
        termRepository = mock(TermRepository.class);
        memberTermAgreementRepository = mock(MemberTermAgreementRepository.class);
        pointWalletRepository = mock(PointWalletRepository.class);
        verificationCodeStore = new InMemoryVerificationCodeStore();
        riderSignupService = new RiderSignupService(
                memberRepository, riderProfileRepository, termRepository, memberTermAgreementRepository,
                pointWalletRepository, verificationCodeStore);

        when(memberRepository.existsByLoginId(any())).thenReturn(false);
        when(memberRepository.existsByPhoneNumber(any())).thenReturn(false);
        when(memberRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(riderProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private RiderSignupRequest request(List<Long> agreedTermIds) {
        return new RiderSignupRequest(
                LOGIN_ID, "p@ssw0rd", "p@ssw0rd", "홍길동", PHONE_NUMBER, TOKEN, agreedTermIds);
    }

    private void issueVerifiedToken(VerificationPurpose purpose, String phoneNumber) {
        verificationCodeStore.saveVerifiedToken(TOKEN, purpose, phoneNumber, Duration.ofMinutes(10));
    }

    private Term term(Long id, boolean required) {
        Term term = Term.create("SERVICE", TermTargetRole.RIDER, "약관", "본문", "1.0",
                required, LocalDateTime.of(2026, 1, 1, 0, 0), null);
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

        RiderSignupResult result = riderSignupService.signup(request(List.of(1L)));

        assertThat(result.loginId()).isEqualTo(LOGIN_ID);
        assertThat(result.name()).isEqualTo("홍길동");
        assertThat(new BCryptPasswordEncoder().matches("p@ssw0rd",
                captureSavedMemberPasswordHash())).isTrue();
    }

    private String captureSavedMemberPasswordHash() {
        ArgumentCaptor<com.turkey.quick.member.domain.Member> captor =
                ArgumentCaptor.forClass(com.turkey.quick.member.domain.Member.class);
        verify(memberRepository).save(captor.capture());
        return captor.getValue().getPasswordHash();
    }

    @Test
    @DisplayName("정상 가입하면 초기 운행 상태가 UNAVAILABLE인 라이더 프로필을 생성한다")
    void shouldCreateUnavailableRiderProfileOnSignup() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());

        riderSignupService.signup(request(List.of()));

        ArgumentCaptor<RiderProfile> captor = ArgumentCaptor.forClass(RiderProfile.class);
        verify(riderProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("정상 가입하면 포인트 지갑을 생성한다")
    void shouldCreatePointWalletOnSignup() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());

        riderSignupService.signup(request(List.of()));

        ArgumentCaptor<PointWallet> captor = ArgumentCaptor.forClass(PointWallet.class);
        verify(pointWalletRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isZero();
    }

    @Test
    @DisplayName("정상 가입하면 동의한 약관마다 동의 이력을 저장한다")
    void shouldSaveAgreementHistoryForEveryAgreedTerm() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any()))
                .thenReturn(List.of(term(1L, true), term(2L, false)));

        riderSignupService.signup(request(List.of(1L, 2L)));

        ArgumentCaptor<List<MemberTermAgreement>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberTermAgreementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("비밀번호와 비밀번호확인이 다르면 거부한다")
    void shouldRejectWhenPasswordConfirmationDoesNotMatch() {
        var mismatched = new RiderSignupRequest(
                LOGIN_ID, "p@ssw0rd", "different", "홍길동", PHONE_NUMBER, TOKEN, List.of());

        assertThatThrownBy(() -> riderSignupService.signup(mismatched))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(memberRepository, never()).save(any());
        verify(riderProfileRepository, never()).save(any());
        verify(pointWalletRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 사용중인 아이디면 거부한다")
    void shouldRejectAlreadyUsedLoginId() {
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("인증 토큰이 없거나 만료됐으면 거부한다")
    void shouldRejectMissingOrExpiredVerificationToken() {
        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("필수 약관에 동의하지 않으면 거부한다")
    void shouldRejectMissingRequiredTermAgreement() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of(term(1L, true)));

        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(memberRepository, never()).save(any());
        verify(riderProfileRepository, never()).save(any());
        verify(pointWalletRepository, never()).save(any());
    }

    @Test
    @DisplayName("사전 중복 확인을 통과해도 DB 유니크 제약을 어기면 409로 변환한다")
    void shouldTranslateDatabaseUniqueConstraintViolationToConflict() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());
        when(memberRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_member_login_id"));

        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(riderProfileRepository, never()).save(any());
        verify(pointWalletRepository, never()).save(any());
    }
}
