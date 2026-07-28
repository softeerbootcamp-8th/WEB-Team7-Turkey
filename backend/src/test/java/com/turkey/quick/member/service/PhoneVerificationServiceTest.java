package com.turkey.quick.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PhoneVerificationServiceTest {

    private static final String PHONE_NUMBER = "01012345678";

    private MemberRepository memberRepository;
    private InMemoryVerificationCodeStore verificationCodeStore;
    private FakeSmsSender smsSender;
    private PhoneVerificationService phoneVerificationService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        verificationCodeStore = new InMemoryVerificationCodeStore();
        smsSender = new FakeSmsSender();
        phoneVerificationService = new PhoneVerificationService(memberRepository, verificationCodeStore, smsSender);
    }

    @Test
    void 미가입_번호로_회원가입_목적_인증번호를_요청하면_발송하고_저장한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);

        PhoneVerificationResult result = phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThat(result.code()).hasSize(6);
        assertThat(smsSender.lastPhoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(smsSender.lastCode()).isEqualTo(result.code());
        assertThat(verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER)).isEqualTo(result.code());
    }

    @Test
    void 이미_가입된_번호로_회원가입_목적_인증번호를_요청하면_거부한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 계정찾기_목적은_가입_여부를_확인하지_않는다() {
        // FIND_ID 목적은 이슈 처리 흐름상 회원가입 여부 확인 단계(③)를 건너뛴다.
        // existsByPhoneNumber 가 true 를 반환하도록 둬도(=미가입이 아니어도) 거부되지 않아야 분기가 맞다.
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        PhoneVerificationResult result = phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.FIND_ID);

        assertThat(result.code()).isNotNull();
    }

    @Test
    void 재전송_쿨다운_중이면_거부한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void 문자_발송에_실패하면_502로_변환한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        smsSender.failNext();

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
