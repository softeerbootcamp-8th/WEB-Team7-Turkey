package com.turkey.quick.rider.domain;

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
 * 라이더 포인트 출금 요청 및 실패 복구 상태.
 *
 * PENDING 에서 시작해 complete()→COMPLETED 또는 fail()→FAILED 로 끝난다. 한 번 처리된 출금은
 * 다시 처리할 수 없다(재시도는 새 request_key 로 새 요청을 만든다).
 *
 * 계좌 정보는 요청 시점 값을 스냅샷으로 복사해 보관한다 — {@link RiderPayoutAccount}는 라이더당
 * 1행을 in-place 로 교체하므로(changeAccount), 참조만 두면 과거 이력이 현재 계좌를 가리키게 된다.
 * 계좌번호 암호문은 복사하지 않는다: 이력 조회에는 마스킹 값이면 충분하고 암호문 사본을 늘리면
 * 유출 표면만 넓어진다.
 *
 * 출금은 선차감 모델이다 — 요청 시 포인트를 먼저 차감하고 실패하면 되돌린다. DB
 * ck_rider_withdrawal_state_values 가 FAILED ⟺ points_restored=1 을 강제하므로,
 * fail() 은 복구 플래그를 함께 세운다. 따라서 <b>실제 포인트 복구(지갑 credit + WITHDRAWAL_REFUND
 * 원장)를 같은 트랜잭션에서 반드시 수행해야 한다</b> — 복구에 실패하면 트랜잭션 전체를 롤백해
 * "실패했는데 포인트는 그대로 사라진" 행이 커밋되지 않게 한다.
 *
 * 멱등성: (rider_id, request_key) 유니크가 동일 출금 요청 재전송을 막는다.
 */
@Entity
@Table(name = "rider_withdrawal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "withdrawal_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id", updatable = false)
    private RiderProfile rider;

    /** CHAR(36) 컬럼(UUID 문자열). String 기본 매핑은 VARCHAR 라 CHAR 로 명시해 validate 를 맞춘다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_key", nullable = false, length = 36, updatable = false)
    private String requestKey;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "bank_code_snapshot", nullable = false, length = 20, updatable = false)
    private String bankCodeSnapshot;

    @Column(name = "masked_account_number_snapshot", nullable = false, length = 50,
            updatable = false)
    private String maskedAccountNumberSnapshot;

    @Column(name = "account_holder_name_snapshot", nullable = false, length = 50, updatable = false)
    private String accountHolderNameSnapshot;

    /**
     * 벤더 이체 식별자(V19, #90). {@code PointCharge.providerPaymentKey}와 같은 이유로 확정
     * 트랜잭션보다 먼저 선커밋한다({@link #markTransferReceived}) — 뒤가 실패해도 "이체는 됐다"는
     * 사실이 DB에 남는다.
     */
    @Column(name = "provider_transfer_key", length = 100)
    private String providerTransferKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WithdrawalStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** 차감했던 포인트를 되돌렸는지 여부. FAILED 일 때만 true 다(DB CHECK 와 동일). */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "points_restored", nullable = false)
    private boolean pointsRestored;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    private RiderWithdrawal(RiderPayoutAccount account, String requestKey, long amount) {
        if (account == null) {
            throw new IllegalArgumentException("출금 계좌는 필수입니다. account=null");
        }
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException(
                    "요청 식별값은 비어 있을 수 없습니다. requestKey=" + requestKey);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("출금 금액은 양수여야 합니다. amount=" + amount);
        }
        this.rider = account.getRider();
        this.requestKey = requestKey;
        this.amount = amount;
        this.bankCodeSnapshot = account.getBankCode();
        this.maskedAccountNumberSnapshot = account.getMaskedAccountNumber();
        this.accountHolderNameSnapshot = account.getAccountHolderName();
        this.status = WithdrawalStatus.PENDING;
        this.pointsRestored = false;
    }

    /**
     * 출금 요청 생성. PENDING 으로 시작한다.
     * 라이더와 계좌 스냅샷을 등록 계좌에서 직접 복사하므로, 계좌 정보를 개별 인자로 받을 때
     * 생기는 불일치(다른 라이더의 계좌를 넘기거나 문자열 인자를 뒤바꾸는 사고)가 원천 차단된다.
     */
    public static RiderWithdrawal request(RiderPayoutAccount account, String requestKey,
                                          long amount) {
        return new RiderWithdrawal(account, requestKey, amount);
    }

    /**
     * 이체 결과 식별자 선커밋(V19, #90). {@code PointCharge.markApprovalReceived}와 같은 계약이다:
     * PENDING 이 아니면 거부하고, 이미 다른 식별자가 있으면 덮어쓰지 않고 같은 값인지만 확인한다.
     *
     * @return 정상 기록(또는 같은 값 재기록)이면 {@code true}, 이미 <b>다른</b> 식별자가 있으면
     *         {@code false}(= 같은 출금에 이체 응답이 둘 존재한다는 신호)
     */
    public boolean markTransferReceived(String providerTransferKey) {
        requirePending("이체 식별자 기록");
        if (this.providerTransferKey != null) {
            return this.providerTransferKey.equals(providerTransferKey);
        }
        this.providerTransferKey = providerTransferKey;
        return true;
    }

    /** 송금 성공: PENDING → COMPLETED. 차감된 포인트는 그대로 두므로 복구 플래그는 false 다. */
    public void complete() {
        requirePending("완료 처리");
        this.status = WithdrawalStatus.COMPLETED;
        this.processedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 송금 실패: PENDING → FAILED. 복구 플래그를 함께 세운다(ck_rider_withdrawal_state_values).
     * 호출자는 같은 트랜잭션에서 실제 포인트 복구까지 완료해야 한다 — 복구가 실패하면
     * 이 행도 함께 롤백되어야 하기 때문이다.
     */
    public void fail(String failureReason) {
        requirePending("실패 처리");
        this.status = WithdrawalStatus.FAILED;
        this.failureReason = failureReason;
        this.pointsRestored = true;
        this.processedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requirePending(String action) {
        if (this.status != WithdrawalStatus.PENDING) {
            throw new IllegalStateException(
                    action + " 불가: 이미 처리된 출금입니다. 현재 상태 " + status
                            + " (withdrawalId=" + id + ")");
        }
    }

    /* 처리 시각은 processed_at 에 따로 남기므로 updated_at 을 두지 않는다. */
    @PrePersist
    void onCreate() {
        this.requestedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
