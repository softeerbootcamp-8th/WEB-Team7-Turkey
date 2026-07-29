package com.turkey.quick.member.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class LoginIdE2ETest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void 사용_가능한_아이디를_조회하면_200과_available_true를_반환한다() {
        var response = rest.getForEntity("/api/login-ids/availability?loginId=fresh_id_1", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("available", true);
    }

    @Test
    void 사용중인_아이디를_조회하면_200과_available_false_사유를_반환한다() {
        memberRepository.save(Member.create("already_taken", "hash", "테스터3", "01055556666", MemberRole.CUSTOMER));

        var response = rest.getForEntity("/api/login-ids/availability?loginId=already_taken", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("available", false)
                .containsKey("reason");
    }

    @Test
    void loginId_파라미터가_비어있으면_400을_반환한다() {
        var response = rest.getForEntity("/api/login-ids/availability?loginId=", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
