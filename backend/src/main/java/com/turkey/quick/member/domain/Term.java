package com.turkey.quick.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * 버전별 회원가입 약관.
 * 같은 term_code 라도 문구가 바뀌면 새 version 으로 행을 추가하고, (term_code, target_role, version)
 * 이 유일하다. 회원의 동의는 특정 term 행(=특정 버전)을 가리키므로 약관 원문은 사실상 불변이다.
 *
 * version 은 약관 "문서 버전 문자열"(예: "1.0")이며 낙관적 락(@Version)이 아니다.
 * 팀 정책상 낙관적 락은 전면 폐기했으므로 이 필드에 @Version 을 붙이지 않는다.
 */
@Entity
@Table(name = "term")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Term {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_id")
    private Long id;

    @Column(name = "term_code", nullable = false, length = 50, updatable = false)
    private String termCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", nullable = false, length = 20, updatable = false)
    private TermTargetRole targetRole;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    /** 약관 본문. MEDIUMTEXT 컬럼이라 대용량 문자열을 담는다. */
    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** 약관 문서 버전 문자열. 낙관적 락 아님(주석 참고). */
    @Column(name = "version", nullable = false, length = 30, updatable = false)
    private String version;

    /** 필수 동의 여부. TINYINT(1) 컬럼에 0/1 로 저장한다. */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "is_required", nullable = false, updatable = false)
    private boolean required;

    /** 노출/사용 가능 여부. 신규 버전 등장 시 구버전을 비활성화(deactivate)한다. */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Term(String termCode, TermTargetRole targetRole, String title, String content,
                 String version, boolean required, LocalDateTime effectiveFrom,
                 LocalDateTime effectiveTo) {
        this.termCode = termCode;
        this.targetRole = targetRole;
        this.title = title;
        this.content = content;
        this.version = version;
        this.required = required;
        this.active = true;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    /** 신규 약관 버전 등록. 기본적으로 활성(active=true) 상태로 생성된다. */
    public static Term create(String termCode, TermTargetRole targetRole, String title,
                              String content, String version, boolean required,
                              LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        return new Term(termCode, targetRole, title, content, version, required,
                effectiveFrom, effectiveTo);
    }

    /** 상위 버전 등장 등으로 더 이상 노출하지 않도록 비활성화한다. */
    public void deactivate() {
        this.active = false;
    }

    /** 주어진 시점에 실제로 유효한(활성 + 유효기간 내) 약관인지 판단한다. */
    public boolean isEffectiveAt(LocalDateTime at) {
        if (!active) {
            return false;
        }
        if (at.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || at.isBefore(effectiveTo);
    }

    /* 타임스탬프 정책: 애플리케이션이 UTC 를 기록한다(DB DEFAULT 는 폴백).
       term 은 불변 이력 성격이라 updated_at 컬럼이 없으므로 @PreUpdate 도 두지 않는다. */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
