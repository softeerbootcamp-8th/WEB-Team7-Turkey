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

    @Test
    void 문자_발송에_실패하면_저장된_코드와_쿨다운을_지워_즉시_재시도할_수_있다() {
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
    void 동시에_같은_번호로_요청하면_한_건만_성공한다() throws InterruptedException {
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
    void 올바른_코드로_확인하면_토큰을_발급하고_코드를_지운다() {
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
    void 인증_요청_이력이_없으면_404를_반환한다() {
        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 인증번호가_틀리면_400을_반환한다() {
        when(memberRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        phoneVerificationService.request(PHONE_NUMBER, VerificationPurpose.SIGNUP);

        assertThatThrownBy(() -> phoneVerificationService.confirm(PHONE_NUMBER, VerificationPurpose.SIGNUP, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 오입력을_5회_초과하면_429를_반환하고_코드를_지운다() {
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
    void 검증에_성공한_코드는_다시_사용할_수_없다() {
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
