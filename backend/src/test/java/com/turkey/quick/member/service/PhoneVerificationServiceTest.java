package com.turkey.quick.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("미가입 번호로 회원가입 목적 인증번호를 요청하면 발송하고 저장한다")
    void shouldSendAndStoreSignupCodeForUnregisteredNumber() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);

        PhoneVerificationResult result = phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThat(result.code()).hasSize(6);
        assertThat(smsSender.lastPhoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(smsSender.lastCode()).isEqualTo(result.code());
        assertThat(verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER)).isEqualTo(result.code());
    }

    @Test
    @DisplayName("이미 가입된 번호로 회원가입 목적 인증번호를 요청하면 거부한다")
    void shouldRejectSignupCodeRequestForRegisteredNumber() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("계정찾기 목적은 가입 여부를 확인하지 않는다")
    void shouldSkipRegistrationCheckForAccountRecovery() {
        // FIND_ID 목적은 이슈 처리 흐름상 회원가입 여부 확인 단계(③)를 건너뛴다.
        // existsByPhoneNumber 가 true 를 반환하도록 둬도(=미가입이 아니어도) 거부되지 않아야 분기가 맞다.
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        PhoneVerificationResult result = phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.FIND_ID);

        assertThat(result.code()).isNotNull();
    }

    @Test
    @DisplayName("재전송 쿨다운 중이면 거부한다")
    void shouldRejectRequestDuringResendCooldown() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("문자 발송에 실패하면 502로 변환한다")
    void shouldTranslateSmsFailureToBadGateway() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        smsSender.failNext();

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("문자 발송에 실패하면 저장된 코드와 쿨다운을 지워 즉시 재시도할 수 있다")
    void shouldClearCodeAndCooldownAfterSmsFailure() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        smsSender.failNext();

        assertThatThrownBy(() -> phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class);

        assertThat(verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER)).isNull();

        // 쿨다운이 남아 있었다면 여기서 429 로 막혔을 것이다.
        PhoneVerificationResult retry = phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);
        assertThat(retry.code()).isNotNull();
    }

    @Test
    @DisplayName("동시에 같은 번호로 요청하면 한 건만 성공한다")
    void shouldAllowOnlyOneConcurrentRequestForSameNumber() throws InterruptedException {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);

        int 시도수 = 10;
        var 시작 = new CountDownLatch(1);
        var 완료 = new CountDownLatch(시도수);
        var 성공 = new AtomicInteger();
        var 실패 = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(시도수)) {
            for (int i = 0; i < 시도수; i++) {
                pool.submit(() -> {
                    try {
                        시작.await();
                        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);
                        성공.incrementAndGet();
                    } catch (BusinessException e) {
                        실패.incrementAndGet(); // 쿨다운 선점 실패 = 정상적인 경쟁 패배
                    } catch (Exception ignored) {
                    } finally {
                        완료.countDown();
                    }
                });
            }
            시작.countDown();
            완료.await(5, TimeUnit.SECONDS);
        }

        assertThat(성공.get()).isEqualTo(1);
        assertThat(실패.get()).isEqualTo(시도수 - 1);
    }

    @Test
    @DisplayName("올바른 코드로 확인하면 토큰을 발급하고 코드를 지운다")
    void shouldIssueTokenAndDeleteCodeAfterSuccessfulConfirmation() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);
        String code = verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER);

        PhoneVerificationConfirmResult result = phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, code);

        assertThat(result.verificationToken()).isNotBlank();
        assertThat(verificationCodeStore.verifiedTokenValue(result.verificationToken()))
                .isEqualTo(VerificationPurpose.SIGNUP + ":" + PHONE_NUMBER);
        assertThat(verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER)).isNull();
    }

    @Test
    @DisplayName("인증 요청 이력이 없으면 404를 반환한다")
    void shouldReturnNotFoundWithoutVerificationRequestHistory() {
        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("인증번호가 틀리면 400을 반환한다")
    void shouldReturnBadRequestForWrongVerificationCode() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("오입력을 5회 초과하면 429를 반환하고 코드를 지운다")
    void shouldReturnTooManyRequestsAndDeleteCodeAfterTooManyFailures() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "000000"))
                    .isInstanceOf(BusinessException.class);
        }

        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER)).isNull();
    }

    @Test
    @DisplayName("검증에 성공한 코드는 다시 사용할 수 없다")
    void shouldNotReuseSuccessfullyVerifiedCode() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);
        String code = verificationCodeStore.savedCode(VerificationPurpose.SIGNUP, PHONE_NUMBER);
        phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, code);

        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
