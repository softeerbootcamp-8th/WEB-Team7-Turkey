package com.turkey.quick.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("동의 이력은 대상 회원과 약관을 참조한다")
    void shouldReferenceMemberAndTerm() {
        Member member = member();
        Term term = term();

        MemberTermAgreement agreement = MemberTermAgreement.create(member, term, true);

        assertThat(agreement.getMember()).isSameAs(member);

        assertThat(agreement.getTerm()).isSameAs(term);
    }

    @Test
    @DisplayName("동의한 경우 agreed는 true다")
    void shouldSetAgreedTrueWhenAccepted() {
        MemberTermAgreement agreement = MemberTermAgreement.create(member(), term(), true);

        assertThat(agreement.isAgreed()).isTrue();
    }

    @Test
    @DisplayName("동의하지 않은 경우 agreed는 false다")
    void shouldSetAgreedFalseWhenDeclined() {
        MemberTermAgreement agreement = MemberTermAgreement.create(member(), term(), false);

        assertThat(agreement.isAgreed()).isFalse();
    }
}
