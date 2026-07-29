package com.turkey.quick.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.support.IntegrationTestSupport;
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
    void 가입된_휴대전화_번호는_존재_여부가_true다() {
        memberRepository.save(Member.create("tester1", "hash", "테스터", "01011112222", MemberRole.CUSTOMER));

        assertThat(memberRepository.existsByPhoneNumber("01011112222")).isTrue();
    }

    @Test
    void 가입되지_않은_휴대전화_번호는_존재_여부가_false다() {
        assertThat(memberRepository.existsByPhoneNumber("01099998888")).isFalse();
    }

    @Test
    void 사용중인_로그인_아이디는_존재_여부가_true다() {
        memberRepository.save(Member.create("taken_id", "hash", "테스터2", "01022223333", MemberRole.CUSTOMER));

        assertThat(memberRepository.existsByLoginId("taken_id")).isTrue();
    }

    @Test
    void 사용중이지_않은_로그인_아이디는_존재_여부가_false다() {
        assertThat(memberRepository.existsByLoginId("nobody_uses_this")).isFalse();
    }
}
