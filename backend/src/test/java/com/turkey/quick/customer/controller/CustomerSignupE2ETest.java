package com.turkey.quick.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.domain.Term;
import com.turkey.quick.member.domain.TermTargetRole;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.member.repository.MemberTermAgreementRepository;
import com.turkey.quick.member.repository.TermRepository;
import com.turkey.quick.member.service.FakeSmsSender;
import com.turkey.quick.member.service.VerificationCodeStore;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * 로컬 Redis 없이 돌리기 위해 VerificationCodeStore/SmsSender 를 인메모리 테스트 대체로 교체한다
 * (PhoneVerificationE2ETest 와 동일한 이유).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class CustomerSignupE2ETest extends IntegrationTestSupport {

    private static final String SIGNUP_ENDPOINT = "/api/customer/signup";
    private static final String PHONE_VERIFICATION_ENDPOINT = "/api/phone-verifications";
    private static final String PHONE_VERIFICATION_CONFIRM_ENDPOINT = "/api/phone-verifications/confirm";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private MemberTermAgreementRepository memberTermAgreementRepository;

    @Autowired
    private VerificationCodeStore verificationCodeStore;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        FakeSmsSender smsSender() {
            return new FakeSmsSender();
        }
    }

    @AfterEach
    void cleanupCreatedTerms() {
        // resolveAgreedTerms는 role별 "현재 활성인 모든 필수 약관"을 검사하므로, 테스트가 남긴
        // Term이 다른 테스트의 필수 약관 체크에 새어 들어가지 않도록 매번 정리한다.
        // member_term_agreement가 term을 FK로 참조하므로 자식부터 지운다.
        memberTermAgreementRepository.deleteAll();
        termRepository.deleteAll();
    }

    private Term saveRequiredTerm(String code) {
        return termRepository.save(Term.create(code, TermTargetRole.COMMON, "필수 약관", "본문", "1.0",
                true, LocalDateTime.of(2026, 1, 1, 0, 0), null));
    }

    private String issueVerifiedToken(String phoneNumber) {
        String token = "e2e-token-" + phoneNumber;
        verificationCodeStore.saveVerifiedToken(token, VerificationPurpose.SIGNUP, phoneNumber, Duration.ofMinutes(10));
        return token;
    }

    @Test
    @DisplayName("인증번호 요청부터 확인까지 거쳐 발급된 토큰으로 가입하면 200과 생성된 계정을 반환한다")
    void shouldCreateAccountAfterCompletingPhoneVerification() {
        String phoneNumber = "010-1111-2222";
        rest.postForEntity(PHONE_VERIFICATION_ENDPOINT,
                Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.SIGNUP), ApiResponse.class);
        String code = verificationCodeStore.getCode(VerificationPurpose.SIGNUP, "01011112222");
        var confirmResponse = rest.postForEntity(PHONE_VERIFICATION_CONFIRM_ENDPOINT,
                Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.SIGNUP, "code", code), ApiResponse.class);
        String token = (String) ((Map<?, ?>) confirmResponse.getBody().data()).get("verificationToken");

        var signupRequest = Map.of(
                "loginId", "e2e_user01",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("loginId", "e2e_user01");
        assertThat(memberRepository.existsByLoginId("e2e_user01")).isTrue();
        Long memberId = memberRepository.findByLoginId("e2e_user01").orElseThrow().getId();
        PointWallet wallet = pointWalletRepository.findById(memberId).orElseThrow();
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    @DisplayName("필수 약관에 동의하지 않으면 400을 반환한다")
    void shouldReturnBadRequestWithoutRequiredTermAgreement() {
        Term required = saveRequiredTerm("SIGNUP_TERM_MISSING");
        String phoneNumber = "01033334444";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_user02",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터2",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(memberRepository.existsByLoginId("e2e_user02")).isFalse();
    }

    @Test
    @DisplayName("필수 약관에 동의하면 동의 이력과 함께 가입된다")
    void shouldSaveAgreementHistoryWhenRequiredTermsAreAccepted() {
        Term required = saveRequiredTerm("SIGNUP_TERM_OK");
        String phoneNumber = "01044445555";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_user03",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터3",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of(required.getId()));

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(memberRepository.existsByLoginId("e2e_user03")).isTrue();
    }

    @Test
    @DisplayName("이미 사용중인 아이디로 가입하면 409를 반환한다")
    void shouldReturnConflictForAlreadyUsedLoginId() {
        memberRepository.save(Member.create("taken_login", "hash", "기존회원", "01055556666", MemberRole.CUSTOMER));
        String phoneNumber = "01077778888";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "taken_login",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터4",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("휴대전화 인증 없이 가입하면 400을 반환한다")
    void shouldReturnBadRequestWithoutPhoneVerification() {
        var signupRequest = Map.of(
                "loginId", "e2e_user05",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터5",
                "phoneNumber", "010-9999-0000",
                "phoneVerificationToken", "not-a-real-token",
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("비밀번호와 비밀번호확인이 다르면 400을 반환한다")
    void shouldReturnBadRequestWhenPasswordConfirmationDoesNotMatch() {
        String phoneNumber = "01066667777";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_user06",
                "password", "p@ssw0rd",
                "passwordConfirm", "different",
                "name", "테스터6",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("로그인 ID가 50자를 초과하면 409가 아니라 400을 반환한다")
    void shouldReturnBadRequestForLoginIdLongerThanFiftyCharacters() {
        String phoneNumber = "01088889999";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "a".repeat(51),
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "테스터7",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        // 길이 초과는 형식 오류(400)여야 한다. DB 제약 위반으로 새어나가 409(중복)로 오인되면 안 된다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 400을 반환한다")
    void shouldReturnBadRequestWhenPasswordShorterThanEightCharacters() {
        String phoneNumber = "01099990000";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_user_short_pw",
                "password", "short1",
                "passwordConfirm", "short1",
                "name", "테스터9",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(memberRepository.existsByLoginId("e2e_user_short_pw")).isFalse();
    }

    @Test
    @DisplayName("약관 ID 목록에 null이 섞이면 500이 아니라 400을 반환한다")
    void shouldReturnBadRequestWhenTermIdListContainsNull() {
        String phoneNumber = "01022223333";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = new java.util.HashMap<String, Object>();
        signupRequest.put("loginId", "e2e_user08");
        signupRequest.put("password", "p@ssw0rd");
        signupRequest.put("passwordConfirm", "p@ssw0rd");
        signupRequest.put("name", "테스터8");
        signupRequest.put("phoneNumber", phoneNumber);
        signupRequest.put("phoneVerificationToken", token);
        signupRequest.put("agreedTermIds", java.util.Arrays.asList((Long) null));

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
