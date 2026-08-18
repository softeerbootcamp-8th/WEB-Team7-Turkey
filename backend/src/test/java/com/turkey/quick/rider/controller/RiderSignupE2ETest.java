package com.turkey.quick.rider.controller;

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
import com.turkey.quick.member.service.VerificationCodeStore;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.repository.RiderProfileRepository;
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
 * 실제 Redis VerificationCodeStore 에 인증 완료 토큰을 직접 넣어 가입 흐름을 태운다(통합·E2E 는
 * 인메모리 대체가 아니라 실제 Docker Redis 에 붙는다, #254). 시나리오는 CustomerSignupE2ETest 와
 * 겹치는 것(형식 오류, 토큰 재사용 등)은 반복하지 않고, 라이더 가입에서만 갈리는 것(라이더 프로필
 * 생성)에 집중한다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderSignupE2ETest extends IntegrationTestSupport {

    private static final String SIGNUP_ENDPOINT = "/api/rider/signup";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private MemberTermAgreementRepository memberTermAgreementRepository;

    @Autowired
    private VerificationCodeStore verificationCodeStore;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @AfterEach
    void cleanupCreatedTerms() {
        memberTermAgreementRepository.deleteAll();
        termRepository.deleteAll();
    }

    private String issueVerifiedToken(String phoneNumber) {
        String token = "e2e-rider-token-" + phoneNumber;
        verificationCodeStore.saveVerifiedToken(token, VerificationPurpose.SIGNUP, phoneNumber, Duration.ofMinutes(10));
        return token;
    }

    @Test
    @DisplayName("정상 가입하면 200과 UNAVAILABLE 상태의 라이더 프로필을 생성한다")
    void shouldCreateUnavailableRiderProfileOnSignup() {
        String phoneNumber = "01011112222";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_rider01",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "라이더1",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Member member = memberRepository.findByLoginId("e2e_rider01").orElseThrow();
        assertThat(member.getRole()).isEqualTo(MemberRole.RIDER);
        var profile = riderProfileRepository.findById(member.getId()).orElseThrow();
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        PointWallet wallet = pointWalletRepository.findById(member.getId()).orElseThrow();
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    @DisplayName("필수 약관에 동의하지 않아도 가입되고 라이더 프로필도 생성된다(#546, 백엔드 필수 약관 검증 제거)")
    void shouldCreateProfileWithoutRequiredAgreement() {
        termRepository.save(Term.create("RIDER_TERM", TermTargetRole.RIDER, "필수 약관", "본문", "1.0",
                true, LocalDateTime.of(2026, 1, 1, 0, 0), null));
        String phoneNumber = "01033334444";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_rider02",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "라이더2",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Member member = memberRepository.findByLoginId("e2e_rider02").orElseThrow();
        assertThat(riderProfileRepository.findById(member.getId())).isPresent();
    }

    @Test
    @DisplayName("이미 사용중인 아이디로 가입하면 409를 반환한다")
    void shouldReturnConflictForAlreadyUsedLoginId() {
        memberRepository.save(Member.create("taken_rider_login", "hash", "기존라이더", "01055556666", MemberRole.RIDER));
        String phoneNumber = "01077778888";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "taken_rider_login",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "라이더3",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 400을 반환한다")
    void shouldReturnBadRequestWhenPasswordShorterThanEightCharacters() {
        String phoneNumber = "01099990000";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_rider_short_pw",
                "password", "short1",
                "passwordConfirm", "short1",
                "name", "라이더9",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(memberRepository.existsByLoginId("e2e_rider_short_pw")).isFalse();
    }

    @Test
    @DisplayName("휴대전화 인증 없이 가입하면 400을 반환한다")
    void shouldReturnBadRequestWithoutPhoneVerification() {
        var signupRequest = Map.of(
                "loginId", "e2e_rider05",
                "password", "p@ssw0rd",
                "passwordConfirm", "p@ssw0rd",
                "name", "라이더5",
                "phoneNumber", "010-9999-0000",
                "phoneVerificationToken", "not-a-real-token",
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
