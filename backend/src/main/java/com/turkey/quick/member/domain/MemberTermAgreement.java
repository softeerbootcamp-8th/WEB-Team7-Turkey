package com.turkey.quick.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 회원의 약관 버전별 동의 이력.
 * 특정 term 행(=특정 약관 버전)에 대한 한 회원의 동의/미동의를 기록하는 불변 이력이다.
 * (member, term) 조합은 유니크(uk_member_term_agreement)라 같은 버전에 중복 기록되지 않는다.
 *
 * member/term 은 @ManyToOne(LAZY) 연관으로 매핑한다 — 동의 이력을 다룰 때 대부분 회원/약관
 * 상세가 즉시 필요하지 않으므로, 실제 접근 시점에만 추가 조회가 나가도록 지연 로딩한다
 * (RiderProfile→Member 매핑과 동일한 방침, OSIV off 이므로 지연 접근은 서비스 계층에서 처리).
 */
@Entity
@Table(name = "member_term_agreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTermAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agreement_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", updatable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "term_id", updatable = false)
    private Term term;

    /** 동의 여부. 필수 약관 미동의는 상위(가입) 흐름에서 막고, 여기엔 결과만 기록한다. */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "agreed", nullable = false, updatable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private LocalDateTime agreedAt;

    private MemberTermAgreement(Member member, Term term, boolean agreed) {
        this.member = member;
        this.term = term;
        this.agreed = agreed;
    }

    /** 회원이 특정 약관 버전에 대해 동의/미동의한 이력을 생성한다. */
    public static MemberTermAgreement create(Member member, Term term, boolean agreed) {
        return new MemberTermAgreement(member, term, agreed);
    }

    /* 불변 이력이라 updated_at 이 없다. 생성 시점(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.agreedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
