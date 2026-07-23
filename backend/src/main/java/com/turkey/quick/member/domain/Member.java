package com.turkey.quick.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공통 회원 계정.
 * 인증(로그인)의 주체이며, 역할별 부가 정보는 rider_profile 등 별도 테이블이 가진다.
 * 상태 변경은 setter 로 덮어쓰지 않고 행위 메서드(withdraw 등)로만 수행한다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50, updatable = false)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    private Member(String loginId, String passwordHash, String name,
                   String phoneNumber, MemberRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
    }

    public static Member create(String loginId, String passwordHash, String name,
                                String phoneNumber, MemberRole role) {
        return new Member(loginId, passwordHash, name, phoneNumber, role);
    }

    /** 탈퇴. status 와 withdrawn_at 은 DB CHECK(ck_member_withdrawn_at)에 의해 항상 쌍으로 움직여야 한다. */
    public void withdraw() {
        if (this.status == MemberStatus.WITHDRAWN) {
            throw new IllegalStateException("이미 탈퇴한 회원입니다. memberId=" + id);
        }
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public boolean isActive() {
        return this.status == MemberStatus.ACTIVE;
    }

    /* 타임스탬프 정책: 애플리케이션이 UTC 를 기록한다(DB DEFAULT 는 폴백). */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
