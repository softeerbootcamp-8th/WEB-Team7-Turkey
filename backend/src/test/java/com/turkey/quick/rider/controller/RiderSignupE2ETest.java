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
import com.turkey.quick.member.service.InMemoryVerificationCodeStore;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * CustomerSignupE2ETest(#25)와 같은 이유로 VerificationCodeStore를 인메모리 대체로 교체한다.
 * 시나리오는 CustomerSignupE2ETest와 겹치는 것(형식 오류, 토큰 재사용 등)은 반복하지 않고,
 * 라이더 가입에서만 갈리는 것(라이더 프로필 생성)에 집중한다.
 */
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
    private InMemoryVerificationCodeStore verificationCodeStore;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        InMemoryVerificationCodeStore verificationCodeStore() {
            return new InMemoryVerificationCodeStore();
        }
    }

    @AfterEach
    void 만든_약관을_정리한다() {
        memberTermAgreementRepository.deleteAll();
        termRepository.deleteAll();
    }

    private String issueVerifiedToken(String phoneNumber) {
        String token = "e2e-rider-token-" + phoneNumber;
        verificationCodeStore.saveVerifiedToken(token, VerificationPurpose.SIGNUP, phoneNumber, Duration.ofMinutes(10));
        return token;
    }

    @Test
    void 정상_가입하면_200과_UNAVAILABLE_상태의_라이더_프로필을_생성한다() {
        String phoneNumber = "01011112222";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_rider01",
                "password", "aaa",
                "passwordConfirm", "aaa",
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
    void 필수_약관에_동의하지_않으면_400을_반환하고_라이더_프로필도_생성되지_않는다() {
        Term required = termRepository.save(Term.create("RIDER_TERM", TermTargetRole.RIDER, "필수 약관", "본문", "1.0",
                true, LocalDateTime.of(2026, 1, 1, 0, 0), null));
        String phoneNumber = "01033334444";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "e2e_rider02",
                "password", "aaa",
                "passwordConfirm", "aaa",
                "name", "라이더2",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(memberRepository.existsByLoginId("e2e_rider02")).isFalse();
    }

    @Test
    void 이미_사용중인_아이디로_가입하면_409를_반환한다() {
        memberRepository.save(Member.create("taken_rider_login", "hash", "기존라이더", "01055556666", MemberRole.RIDER));
        String phoneNumber = "01077778888";
        String token = issueVerifiedToken(phoneNumber);

        var signupRequest = Map.of(
                "loginId", "taken_rider_login",
                "password", "aaa",
                "passwordConfirm", "aaa",
                "name", "라이더3",
                "phoneNumber", phoneNumber,
                "phoneVerificationToken", token,
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 휴대전화_인증_없이_가입하면_400을_반환한다() {
        var signupRequest = Map.of(
                "loginId", "e2e_rider05",
                "password", "aaa",
                "passwordConfirm", "aaa",
                "name", "라이더5",
                "phoneNumber", "010-9999-0000",
                "phoneVerificationToken", "not-a-real-token",
                "agreedTermIds", List.of());

        var response = rest.postForEntity(SIGNUP_ENDPOINT, signupRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
