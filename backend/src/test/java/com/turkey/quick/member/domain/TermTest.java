package com.turkey.quick.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TermTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 0, 0);

    private Term term(LocalDateTime from, LocalDateTime to) {
        return Term.create("SERVICE", TermTargetRole.COMMON, "서비스 이용약관",
                "본문", "1.0", true, from, to);
    }

    @Test
    @DisplayName("생성된 약관은 활성 상태다")
    void shouldCreateActiveTerm() {
        Term term = term(FROM, TO);

        assertThat(term.isActive()).isTrue();
    }

    @Test
    @DisplayName("유효기간 내 시점에는 유효하다")
    void shouldBeEffectiveWithinEffectivePeriod() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2026, 6, 1, 0, 0))).isTrue();
    }

    @Test
    @DisplayName("effective from 이전 시점에는 유효하지 않다")
    void shouldNotBeEffectiveBeforeEffectiveFrom() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2025, 12, 31, 23, 59))).isFalse();
    }

    @Test
    @DisplayName("effective to 시점 이후에는 유효하지 않다")
    void shouldNotBeEffectiveAtOrAfterEffectiveTo() {
        Term term = term(FROM, TO);

        assertThat(term.isEffectiveAt(TO)).isFalse();
    }

    @Test
    @DisplayName("effective to 가 null이면 effective from 이후로 계속 유효하다")
    void shouldRemainEffectiveAfterEffectiveFromWhenEffectiveToIsNull() {
        Term term = term(FROM, null);

        assertThat(term.isEffectiveAt(LocalDateTime.of(2099, 1, 1, 0, 0))).isTrue();
    }

    @Test
    @DisplayName("비활성화하면 유효기간 내라도 유효하지 않다")
    void shouldNotBeEffectiveAfterDeactivation() {
        Term term = term(FROM, TO);

        term.deactivate();

        assertThat(term.isActive()).isFalse();
        assertThat(term.isEffectiveAt(LocalDateTime.of(2026, 6, 1, 0, 0))).isFalse();
    }
}
