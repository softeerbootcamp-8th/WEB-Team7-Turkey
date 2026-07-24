package com.turkey.quick.payment.domain;

import com.turkey.quick.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원별 현재 포인트 잔액.
 * member 와 PK(member_id)를 공유한다(회원당 지갑 1개) — RiderProfile 과 동일한 공유 PK 방식.
 *
 * 동시성: 낙관적 락(@Version)을 두지 않는다(팀 정책상 @Version 전면 폐기). 잔액 차감/환불처럼
 * 경쟁이 생기는 갱신은 서비스 계층의 조건부 UPDATE
 *   (예: UPDATE point_wallet SET balance = balance - :amt WHERE member_id = :id AND balance >= :amt)
 * 로 "잔액 검증 + 차감"을 한 문장에서 원자적으로 처리하고 영향 행 수로 성공/실패를 판정한다.
 * 여기에 point_transaction.request_key 유니크가 멱등성을 더한다.
 *
 * 아래 credit/debit 은 그 도메인 규칙(양수 금액, 잔액 부족 거부)을 표현하고 로드된 엔터티의
 * 잔액을 일관되게 유지하기 위한 메서드다. 경쟁 상황의 정본 갱신은 위 조건부 UPDATE 가 담당한다.
 */
@Entity
@Table(name = "point_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointWallet {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    /** @MapsId: member 연관의 PK 를 위 memberId(=이 테이블 PK)로 복사 → 공유 PK. LAZY 로 불필요한 조인 방지. */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private PointWallet(Member member) {
        this.member = member;
        this.balance = 0L;
    }

    /** 회원 가입 시 잔액 0 인 지갑을 생성한다. */
    public static PointWallet create(Member member) {
        return new PointWallet(member);
    }

    /** 적립/충전/환불 등 잔액 증가. */
    public void credit(long amount) {
        requirePositive(amount);
        this.balance += amount;
    }

    /** 사용/출금 등 잔액 감소. 잔액이 부족하면 거부한다. */
    public void debit(long amount) {
        requirePositive(amount);
        if (this.balance < amount) {
            throw new IllegalStateException(
                    "포인트 잔액이 부족합니다. balance=" + balance + ", amount=" + amount
                            + " (memberId=" + memberId + ")");
        }
        this.balance -= amount;
    }

    private void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("포인트 금액은 양수여야 합니다. amount=" + amount);
        }
    }

    /* point_wallet 은 created_at 이 없고 updated_at 만 있다(DB DEFAULT/ON UPDATE 는 폴백). */
    @PrePersist
    void onCreate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
