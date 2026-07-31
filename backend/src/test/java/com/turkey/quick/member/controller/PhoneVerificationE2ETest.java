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
 * 로컬 Redis 없이 돌리기 위해 VerificationCodeStore/SmsSender 를 인메모리 테스트 대체로 교체한다.
 * MySQL 대신 H2(integration 프로파일)를 쓰는 것과 같은 이유다.
 */
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
    void 미가입_번호로_회원가입_인증번호를_요청하면_200과_만료시각을_반환한다() {
        var request = Map.of("phoneNumber", "010-2222-3333", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("success").isEqualTo(true);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("expiresAt");
    }

    @Test
    void 이미_가입된_번호로_회원가입_목적을_요청하면_409를_반환한다() {
        memberRepository.save(Member.create("existing1", "hash", "기존회원", "01044445555", MemberRole.CUSTOMER));
        var request = Map.of("phoneNumber", "010-4444-5555", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).extracting("success").isEqualTo(false);
    }

    @Test
    void 휴대전화_번호_형식이_틀리면_400을_반환한다() {
        var request = Map.of("phoneNumber", "not-a-phone", "purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 휴대전화_번호가_없으면_400을_반환한다() {
        var request = Map.of("purpose", VerificationPurpose.SIGNUP);

        var response = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 쿨다운_중_재요청하면_429를_반환한다() {
        var request = Map.of("phoneNumber", "010-6666-7777", "purpose", VerificationPurpose.FIND_ID);

        rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        var second = rest.postForEntity(ENDPOINT, request, ApiResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void 문자_발송에_실패해도_재시도는_쿨다운에_막히지_않는다() {
        smsSender.failNext();
        var request = Map.of("phoneNumber", "010-1111-9999", "purpose", VerificationPurpose.FIND_ID);

        var first = rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        var retry = rest.postForEntity(ENDPOINT, request, ApiResponse.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 발급된_인증번호로_확인하면_200과_토큰을_반환한다() {
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
    void 인증_요청_이력_없이_확인하면_404를_반환한다() {
        var confirmRequest = Map.of("phoneNumber", "010-5555-6666", "purpose", VerificationPurpose.SIGNUP, "code", "123456");

        var response = rest.postForEntity(CONFIRM_ENDPOINT, confirmRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 틀린_인증번호로_확인하면_400을_반환한다() {
        var phoneNumber = "010-7777-8888";
        rest.postForEntity(ENDPOINT, Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.FIND_ID), ApiResponse.class);

        var confirmRequest = Map.of("phoneNumber", phoneNumber, "purpose", VerificationPurpose.FIND_ID, "code", "000000");
        var response = rest.postForEntity(CONFIRM_ENDPOINT, confirmRequest, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
