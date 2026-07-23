package com.turkey.quick.rider.domain;

import com.turkey.quick.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 라이더 운행 프로필.
 * member 와 PK(member_id)를 공유한다 — 별도의 자체 식별자를 두지 않고, 소속 member 의 PK를 그대로 쓴다.
 *
 * 상태 변경은 setter 로 덮어쓰지 않고 행위 메서드(goOnline/goOffline/assign/release)로만 수행하며,
 * 허용되지 않은 전이는 예외로 거부한다. operating_status 가 바뀔 때 status_changed_at 을 함께 갱신한다.
 */
@Entity
@Table(name = "rider_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderProfile {

    /**
     * PK 이면서 동시에 member 로의 FK. @MapsId 가 아래 member 연관의 PK를 이 필드로 복사한다.
     * 따라서 이 값을 직접 세팅하지 않고, member 를 넣으면 Hibernate 가 파생시킨다.
     */
    @Id
    @Column(name = "member_id")
    private Long memberId;

    /**
     * @MapsId: 이 연관의 PK(member.id)를 위 memberId(=이 테이블의 PK)에 매핑한다 → 공유 PK.
     * fetch = LAZY: 라이더 프로필을 조회할 때 member 를 항상 함께 SELECT 하지 않는다.
     *   member.getName() 처럼 실제로 접근하는 순간에만 추가 쿼리가 나간다.
     *   EAGER(기본값)로 두면 프로필만 필요한 배차 탐색·상태 변경 경로에서도 member 조인이
     *   매번 붙어 불필요한 I/O 가 생긴다. 단, LAZY 접근은 트랜잭션(영속성 컨텍스트) 안에서만
     *   가능하다 — OSIV 를 꺼 뒀으므로(application.yml) 지연 로딩은 서비스 계층에서 처리한다.
     */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_status", nullable = false, length = 20)
    private OperatingStatus operatingStatus;

    @Column(name = "status_changed_at", nullable = false)
    private LocalDateTime statusChangedAt;

    /**
     * @Version: 낙관적 락. UPDATE 시 WHERE 절에 version 을 포함하고 값을 +1 한다.
     *   두 트랜잭션이 같은 프로필을 동시에 수정하면 나중 커밋이
     *   OptimisticLockException 으로 실패한다 — "한 라이더는 동시에 한 건의 배송만"이라는
     *   불변식을 락 없이(비관적 락 대신) 지키는 1차 방어선이다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private RiderProfile(Member member) {
        this.member = member;
        this.operatingStatus = OperatingStatus.UNAVAILABLE;
        this.statusChangedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 라이더 가입 직후 프로필 생성. 초기 상태는 UNAVAILABLE(아직 운행 시작 전). */
    public static RiderProfile create(Member member) {
        return new RiderProfile(member);
    }

    /** 운행 시작: UNAVAILABLE → AVAILABLE. */
    public void goOnline() {
        transitionTo(OperatingStatus.AVAILABLE, OperatingStatus.UNAVAILABLE);
    }

    /** 운행 종료/로그아웃: AVAILABLE → UNAVAILABLE. 배송 수행 중(BUSY)에는 종료할 수 없다. */
    public void goOffline() {
        transitionTo(OperatingStatus.UNAVAILABLE, OperatingStatus.AVAILABLE);
    }

    /** 배차 확정: AVAILABLE → BUSY. (배송 WAITING→ASSIGNED 와 한 트랜잭션에서 함께 일어난다) */
    public void assign() {
        transitionTo(OperatingStatus.BUSY, OperatingStatus.AVAILABLE);
    }

    /** 배송 완료: BUSY → AVAILABLE. (배송 DELIVERING→COMPLETED 와 한 트랜잭션에서 함께 일어난다) */
    public void release() {
        transitionTo(OperatingStatus.AVAILABLE, OperatingStatus.BUSY);
    }

    public boolean isAvailable() {
        return this.operatingStatus == OperatingStatus.AVAILABLE;
    }

    private void transitionTo(OperatingStatus target, OperatingStatus... allowedFrom) {
        for (OperatingStatus from : allowedFrom) {
            if (this.operatingStatus == from) {
                this.operatingStatus = target;
                this.statusChangedAt = LocalDateTime.now(ZoneOffset.UTC);
                return;
            }
        }
        throw new IllegalStateException(
                "허용되지 않은 라이더 상태 전이: " + operatingStatus + " → " + target
                        + " (memberId=" + memberId + ")");
    }

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
