package com.turkey.quick.payment.domain;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.payment.domain.PointTransactionType.SourceKind;
import com.turkey.quick.rider.domain.RiderWithdrawal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 회원 포인트 증감 불변 원장.
 *
 * 잔액의 정본은 {@link PointWallet#getBalance()} 이고, 이 엔터티는 그 변화를 한 줄씩 남기는
 * append-only 레코드다. 수정 메서드도 updated_at 도 없다.
 *
 * <b>이 엔터티는 지갑 잔액을 직접 바꾸지 않는다.</b> 실제 잔액 갱신은 서비스 계층의 조건부 UPDATE
 * (예: {@code SET balance = balance - :amt WHERE member_id = :id AND balance >= :amt})가 담당하고,
 * 이 원장은 그렇게 확정된 변화를 기록만 한다. 여기서 wallet 을 함께 변경하면 조건부 UPDATE 와
 * 더티 체킹 플러시가 잔액을 두 번 반영하게 된다. 그래서 balanceBefore 를 인자로 받는다 —
 * 호출자가 같은 트랜잭션에서 확정한 변경 직전 잔액을 넘겨야 한다.
 *
 * DB 의 세 CHECK 제약을 앱에서도 구조적으로 보장한다:
 * <ul>
 *   <li>ck_point_transaction_type_direction — direction 을 인자로 받지 않고
 *       {@link PointTransactionType#direction()} 에서 파생한다.</li>
 *   <li>ck_point_transaction_source — 소스별 팩토리가 자기 FK 하나만 세팅하고 나머지는 null 로 둔다.
 *       팩토리와 유형이 어긋나면(예: forOrder 에 CHARGE) 거부한다.</li>
 *   <li>ck_point_transaction_balance — balanceAfter 를 인자로 받지 않고 방향과 금액으로 계산한다.</li>
 * </ul>
 *
 * 멱등성: request_key 전역 유니크 + 소스별 (source_id, transaction_type) 유니크가
 * 재전송·재시도로 원장이 중복 기록되는 것을 막는다.
 */
@Entity
@Table(name = "point_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_transaction_id")
    private Long id;

    /**
     * 대상 지갑. FK 는 member 가 아니라 point_wallet(member_id)을 참조한다 —
     * 지갑 없는 회원의 원장 행을 DB 수준에서 막는다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", updatable = false)
    private PointWallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30, updatable = false)
    private PointTransactionType transactionType;

    /** transactionType 에서 파생된 값. 컬럼으로도 저장해 원장만 읽어도 증감을 알 수 있게 한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private PointDirection direction;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "balance_before", nullable = false, updatable = false)
    private long balanceBefore;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_key", nullable = false, length = 36, updatable = false)
    private String requestKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_order_id", updatable = false)
    private DeliveryOrder deliveryOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "point_charge_id", updatable = false)
    private PointCharge pointCharge;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rider_withdrawal_id", updatable = false)
    private RiderWithdrawal riderWithdrawal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rider_settlement_id", updatable = false)
    private RiderSettlement riderSettlement;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PointTransaction(PointWallet wallet, PointTransactionType transactionType, long amount,
                             long balanceBefore, String requestKey) {
        if (wallet == null) {
            throw new IllegalArgumentException("대상 지갑은 필수입니다. wallet=null");
        }
        if (transactionType == null) {
            throw new IllegalArgumentException("거래 유형은 필수입니다. transactionType=null");
        }
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException(
                    "요청 식별값은 비어 있을 수 없습니다. requestKey=" + requestKey);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("거래 금액은 양수여야 합니다. amount=" + amount);
        }
        if (balanceBefore < 0) {
            throw new IllegalArgumentException(
                    "변경 전 잔액은 0 이상이어야 합니다. balanceBefore=" + balanceBefore);
        }
        this.wallet = wallet;
        this.transactionType = transactionType;
        this.direction = transactionType.direction();
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = computeBalanceAfter(this.direction, balanceBefore, amount);
        this.requestKey = requestKey;
    }

    /**
     * 충전/충전환불 원장. 유형은 CHARGE 또는 CHARGE_REFUND 만 허용한다.
     *
     * @param balanceBefore 이 변경 직전의 확정 잔액
     */
    public static PointTransaction forCharge(PointWallet wallet, PointTransactionType type,
                                             long amount, long balanceBefore, String requestKey,
                                             PointCharge pointCharge) {
        requireSource(type, SourceKind.CHARGE);
        if (pointCharge == null) {
            throw new IllegalArgumentException("충전 원장에는 point_charge 가 필요합니다. pointCharge=null");
        }
        PointTransaction transaction =
                new PointTransaction(wallet, type, amount, balanceBefore, requestKey);
        transaction.pointCharge = pointCharge;
        return transaction;
    }

    /** 주문 결제/취소환불 원장. 유형은 ORDER_USE 또는 ORDER_REFUND 만 허용한다. */
    public static PointTransaction forOrder(PointWallet wallet, PointTransactionType type,
                                            long amount, long balanceBefore, String requestKey,
                                            DeliveryOrder deliveryOrder) {
        requireSource(type, SourceKind.ORDER);
        if (deliveryOrder == null) {
            throw new IllegalArgumentException("주문 원장에는 delivery_order 가 필요합니다. deliveryOrder=null");
        }
        PointTransaction transaction =
                new PointTransaction(wallet, type, amount, balanceBefore, requestKey);
        transaction.deliveryOrder = deliveryOrder;
        return transaction;
    }

    /**
     * 정산 적립 원장. SETTLEMENT 는 이 소스의 유일한 유형이라 유형을 인자로 받지 않는다.
     */
    public static PointTransaction forSettlement(PointWallet wallet, long amount,
                                                 long balanceBefore, String requestKey,
                                                 RiderSettlement riderSettlement) {
        if (riderSettlement == null) {
            throw new IllegalArgumentException(
                    "정산 원장에는 rider_settlement 가 필요합니다. riderSettlement=null");
        }
        PointTransaction transaction = new PointTransaction(wallet,
                PointTransactionType.SETTLEMENT, amount, balanceBefore, requestKey);
        transaction.riderSettlement = riderSettlement;
        return transaction;
    }

    /** 출금 선차감/실패복구 원장. 유형은 WITHDRAWAL 또는 WITHDRAWAL_REFUND 만 허용한다. */
    public static PointTransaction forWithdrawal(PointWallet wallet, PointTransactionType type,
                                                 long amount, long balanceBefore, String requestKey,
                                                 RiderWithdrawal riderWithdrawal) {
        requireSource(type, SourceKind.WITHDRAWAL);
        if (riderWithdrawal == null) {
            throw new IllegalArgumentException(
                    "출금 원장에는 rider_withdrawal 이 필요합니다. riderWithdrawal=null");
        }
        PointTransaction transaction =
                new PointTransaction(wallet, type, amount, balanceBefore, requestKey);
        transaction.riderWithdrawal = riderWithdrawal;
        return transaction;
    }

    /** 팩토리와 거래 유형의 소스 종류가 일치하는지 검증한다(ck_point_transaction_source 방어). */
    private static void requireSource(PointTransactionType type, SourceKind expected) {
        if (type == null) {
            throw new IllegalArgumentException("거래 유형은 필수입니다. transactionType=null");
        }
        if (type.sourceKind() != expected) {
            throw new IllegalArgumentException(
                    "거래 유형과 소스가 맞지 않습니다. type=" + type
                            + "(소스=" + type.sourceKind() + "), 요구 소스=" + expected);
        }
    }

    /**
     * 방향과 금액으로 변경 후 잔액을 도출한다(ck_point_transaction_balance).
     * DEBIT 인데 잔액이 부족하면 거부한다 — balance_after 는 UNSIGNED 라 음수를 담을 수 없고,
     * 애초에 잔액보다 큰 차감은 도메인상 성립하지 않는다.
     */
    private static long computeBalanceAfter(PointDirection direction, long balanceBefore,
                                            long amount) {
        if (direction == PointDirection.CREDIT) {
            return balanceBefore + amount;
        }
        if (balanceBefore < amount) {
            throw new IllegalArgumentException(
                    "차감 금액이 잔액보다 큽니다. balanceBefore=" + balanceBefore + ", amount=" + amount);
        }
        return balanceBefore - amount;
    }

    /* 불변 원장이라 updated_at 이 없다. 생성 시각(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
