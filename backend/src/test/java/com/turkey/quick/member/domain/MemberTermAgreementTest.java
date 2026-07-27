package com.turkey.quick.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MemberTermAgreementTest {

    private Member member() {
        return Member.create("rider01", "hash", "홍길동", "01011112222", MemberRole.RIDER);
    }

    private Term term() {
        return Term.create("SERVICE", TermTargetRole.COMMON, "서비스 이용약관",
                "본문", "1.0", true,
                LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    @Test
    void 동의_이력은_대상_회원과_약관을_참조한다() {
        Member member = member();
        Term term = term();

        MemberTermAgreement agreement = MemberTermAgreement.create(member, term, true);

        assertThat(agreement.getMember()).isSameAs(member);

        assertThat(agreement.getTerm()).isSameAs(term);
    }

    @Test
    void 동의한_경우_agreed는_true다() {
        MemberTermAgreement agreement = MemberTermAgreement.create(member(), term(), true);

        assertThat(agreement.isAgreed()).isTrue();
    }

    @Test
    void 동의하지_않은_경우_agreed는_false다() {
        MemberTermAgreement agreement = MemberTermAgreement.create(member(), term(), false);

        assertThat(agreement.isAgreed()).isFalse();
    }
}
