package com.turkey.quick.member.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class LoginIdE2ETest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("사용 가능한 아이디를 조회하면 200과 available true를 반환한다")
    void shouldReturnAvailableTrueForUnusedLoginId() {
        var response = rest.getForEntity("/api/login-ids/availability?loginId=fresh_id_1", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("available", true);
    }

    @Test
    @DisplayName("사용중인 아이디를 조회하면 200과 available false 사유를 반환한다")
    void shouldReturnAvailableFalseWithReasonForUsedLoginId() {
        memberRepository.save(Member.create("already_taken", "hash", "테스터3", "01055556666", MemberRole.CUSTOMER));

        var response = rest.getForEntity("/api/login-ids/availability?loginId=already_taken", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("available", false)
                .containsKey("reason");
    }

    @Test
    @DisplayName("loginId 파라미터가 비어있으면 400을 반환한다")
    void shouldReturnBadRequestForBlankLoginIdParameter() {
        var response = rest.getForEntity("/api/login-ids/availability?loginId=", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
