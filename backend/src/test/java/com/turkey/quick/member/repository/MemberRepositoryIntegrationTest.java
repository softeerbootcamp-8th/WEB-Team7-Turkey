package com.turkey.quick.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class MemberRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("가입된 휴대전화 번호는 존재 여부가 true다")
    void shouldFindRegisteredPhoneNumber() {
        memberRepository.save(Member.create("tester1", "hash", "테스터", "01011112222", MemberRole.CUSTOMER));

        assertThat(memberRepository.existsByPhoneNumber("01011112222")).isTrue();
    }

    @Test
    @DisplayName("가입되지 않은 휴대전화 번호는 존재 여부가 false다")
    void shouldNotFindUnregisteredPhoneNumber() {
        assertThat(memberRepository.existsByPhoneNumber("01099998888")).isFalse();
    }

    @Test
    @DisplayName("사용중인 로그인 아이디는 존재 여부가 true다")
    void shouldFindUsedLoginId() {
        memberRepository.save(Member.create("taken_id", "hash", "테스터2", "01022223333", MemberRole.CUSTOMER));

        assertThat(memberRepository.existsByLoginId("taken_id")).isTrue();
    }

    @Test
    @DisplayName("사용중이지 않은 로그인 아이디는 존재 여부가 false다")
    void shouldNotFindUnusedLoginId() {
        assertThat(memberRepository.existsByLoginId("nobody_uses_this")).isFalse();
    }
}
