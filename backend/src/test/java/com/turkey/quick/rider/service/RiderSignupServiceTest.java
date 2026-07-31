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
    void 정상_가입하면_비밀번호를_해시해_저장하고_결과를_반환한다() {
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
    void 정상_가입하면_초기_운행_상태가_UNAVAILABLE인_라이더_프로필을_생성한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());

        riderSignupService.signup(request(List.of()));

        ArgumentCaptor<RiderProfile> captor = ArgumentCaptor.forClass(RiderProfile.class);
        verify(riderProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    void 정상_가입하면_포인트_지갑을_생성한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any())).thenReturn(List.of());

        riderSignupService.signup(request(List.of()));

        ArgumentCaptor<PointWallet> captor = ArgumentCaptor.forClass(PointWallet.class);
        verify(pointWalletRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isZero();
    }

    @Test
    void 정상_가입하면_동의한_약관마다_동의_이력을_저장한다() {
        issueVerifiedToken(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        when(termRepository.findByActiveTrueAndTargetRoleIn(any()))
                .thenReturn(List.of(term(1L, true), term(2L, false)));

        riderSignupService.signup(request(List.of(1L, 2L)));

        ArgumentCaptor<List<MemberTermAgreement>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberTermAgreementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void 비밀번호와_비밀번호확인이_다르면_거부한다() {
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
    void 이미_사용중인_아이디면_거부한다() {
        when(memberRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 인증_토큰이_없거나_만료됐으면_거부한다() {
        assertThatThrownBy(() -> riderSignupService.signup(request(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 필수_약관에_동의하지_않으면_거부한다() {
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
    void 사전_중복_확인을_통과해도_DB_유니크_제약을_어기면_409로_변환한다() {
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
