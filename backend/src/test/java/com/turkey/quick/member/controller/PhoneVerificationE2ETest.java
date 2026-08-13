package com.turkey.quick.member.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.member.service.FakeSmsSender;
import com.turkey.quick.member.service.VerificationCodeStore;
import com.turkey.quick.support.IntegrationTestSupport;
import java.util.Map;
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
 * 로컬 Redis 없이 돌리기 위해 VerificationCodeStore/SmsSender 를 인메모리 테스트 대체로 교체한다.
 * MySQL 대신 H2(integration 프로파일)를 쓰는 것과 같은 이유다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class PhoneVerificationE2ETest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/api/phone-verifications";
    private static final String CONFIRM_ENDPOINT = "/api/phone-verifications/confirm";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private FakeSmsSender smsSender;

    @Autowired
    private VerificationCodeStore verificationCodeStore;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        FakeSmsSender smsSender() {
            return new FakeSmsSender();
        }
    }

    @Test
    @DisplayName("미가입 번호로 회원가입 인증번호를 요청하면 200과 만료시각을 반환한다")
    void shouldReturnExpirationForSignupCodeRequestFromUnregisteredNumber() {
        var request = Map.of("phoneNumber", "010-2222-3333", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("success").isEqualTo(true);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("expiresAt");
    }

    @Test
    @DisplayName("이미 가입된 번호로 회원가입 목적을 요청하면 409를 반환한다")
    void shouldReturnConflictForSignupCodeRequestFromRegisteredNumber() {
        memberRepository.save(Member.create("existing1", "hash", "기존회원", "01044445555", MemberRole.CUSTOMER));
        var request = Map.of("phoneNumber", "010-4444-5555", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).extracting("success").isEqualTo(false);
    }

    @Test
    @DisplayName("휴대전화 번호 형식이 틀리면 400을 반환한다")
    void shouldReturnBadRequestForInvalidPhoneNumberFormat() {
        var request = Map.of("phoneNumber", "not-a-phone", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("휴대전화 번호가 없으면 400을 반환한다")
    void shouldReturnBadRequestWithoutPhoneNumber() {
        var request = Map.of("purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("쿨다운 중 재요청하면 429를 반환한다")
    void shouldReturnTooManyRequestsDuringCooldown() {
        var request = Map.of("phoneNumber", "010-6666-7777", "purpose", VerificationPurpose.FIND_ID);

        rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        var second = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("문자 발송에 실패해도 재시도는 쿨다운에 막히지 않는다")
    void shouldAllowImmediateRetryAfterSmsFailure() {
        smsSender.failNext();
        var request = Map.of("phoneNumber", "010-1111-9999", "purpose", VerificationPurpose.FIND_ID);

        var first = rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        var retry = rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("발급된 인증번호로 확인하면 200과 토큰을 반환한다")
    void shouldReturnTokenForValidVerificationCode() {
        var phoneNumber = "010-3333-4444";
        rest.postForEntity(ENDPOINT, Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.SIGNUP), ApiResponse.class);
        String code = verificationCodeStore.getCode(VerificationPurpose.SIGNUP, "01033334444");

        var confirmRequest = Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.SIGNUP, "code", code);
        var response = rest.postForEntity(CONFIRM_ENDPOINT, confirmRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("verificationToken");
    }

    @Test
    @DisplayName("인증 요청 이력 없이 확인하면 404를 반환한다")
    void shouldReturnNotFoundWithoutVerificationRequestHistory() {
        var confirmRequest = Map.of("phoneNumber", "010-5555-6666", "purpose", VerificationPurpose.SIGNUP, "code", "123456");

        var response = rest.postForEntity(CONFIRM_ENDPOINT, confirmRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("틀린 인증번호로 확인하면 400을 반환한다")
    void shouldReturnBadRequestForWrongVerificationCode() {
        var phoneNumber = "010-7777-8888";
        rest.postForEntity(ENDPOINT, Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.FIND_ID), ApiResponse.class);

        var confirmRequest = Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.FIND_ID, "code", "000000");
        var response = rest.postForEntity(CONFIRM_ENDPOINT, confirmRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
