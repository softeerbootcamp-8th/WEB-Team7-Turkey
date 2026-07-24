package com.turkey.quick.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TermTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 0, 0);

    private Term term(LocalDateTime from, LocalDateTime to) {
        return Term.create("SERVICE", TermTargetRole.COMMON, "서비스 이용약관",
                "본문", "1.0", true, from, to);
    }

    @Test
    void 생성된_약관은_활성_상태다() {
        Term term = term(FROM, TO);

        assertThat(term.isActive()).isTrue();
    }

    @Test
    void 유효기간_내_시점에는_유효하다() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2026, 6, 1, 0, 0))).isTrue();
    }

    @Test
    void effective_from_이전_시점에는_유효하지_않다() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2025, 12, 31, 23, 59))).isFalse();
    }

    @Test
    void effective_to_시점_이후에는_유효하지_않다() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(TO)).isFalse();
    }

    @Test
    void effective_to_가_null이면_effective_from_이후로_계속_유효하다() {
        Term term = term(FROM, null);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2099, 1, 1, 0, 0))).isTrue();
    }

    @Test
    void 비활성화하면_유효기간_내라도_유효하지_않다() {
        Term term = term(FROM, TO);

        term.deactivate();

        assertThat(term.isActive()).isFalse();
        assertThat(term.isEffectiveAt(LocalDateTime.of(2026, 6, 1, 0, 0))).isFalse();
    }
}
