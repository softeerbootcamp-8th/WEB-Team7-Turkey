package com.turkey.quick.payment.domain;

import com.turkey.quick.member.domain.Member;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 고객 포인트 충전 결제 및 전액 환불 상태.
 * PENDING 에서 시작해 approve→PAID / fail→FAILED / cancel→CANCELED 로 갈리고,
 * PAID 는 refund→REFUNDED(전액 환불)로만 전이한다. 부분 환불은 없다.
 *
 * 각 상태의 필드 조합(approved_amount/approved_at/refunded_amount/refunded_at)은 DB
 * ck_point_charge_state_values 가 강제한다 — 아래 전이 메서드는 그 조합을 정확히 맞춰 세팅해
 * 앱과 DB 양쪽에서 불변식을 지킨다.
 *
 * 멱등성: (customer, charge_request_key) 유니크로 동일 충전요청 재전송을 막고,
 * provider_payment_key / provider_refund_key 유니크로 PG 중복 승인·환불을 막는다.
 */
@Entity
@Table(name = "point_charge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_charge_id")
    private Long id;

    /** 충전 주체(고객). 결제는 고객당 발생하므로 member 로 매핑하고 LAZY 로 조인을 지연한다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private Member customer;

    /** CHAR(36) 컬럼(UUID 문자열). String 기본 매핑은 VARCHAR 라 CHAR 로 명시해 validate 를 맞춘다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "charge_request_key", nullable = false, length = 36, updatable = false)
    private String chargeRequestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20, updatable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_provider", length = 30)
    private String paymentProvider;

    @Column(name = "provider_payment_key", length = 100)
    private String providerPaymentKey;

    @Column(name = "provider_refund_key", length = 100)
    private String providerRefundKey;

    @Column(name = "requested_amount", nullable = false, updatable = false)
    private long requestedAmount;

    /** 승인 금액. 전액 결제만 있으므로 PAID 시 requested_amount 와 같다. 미승인 시 null. */
    @Column(name = "approved_amount")
    private Long approvedAmount;

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PointChargeStatus status;

    @Column(name = "issuer_code", length = 20)
    private String issuerCode;

    @Column(name = "masked_payment_method", length = 100)
    private String maskedPaymentMethod;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "refund_reason", length = 255)
    private String refundReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private PointCharge(Member customer, String chargeRequestKey, PaymentMethod paymentMethod,
                        long requestedAmount, String paymentProvider) {
        if (requestedAmount <= 0) {
            throw new IllegalArgumentException("충전 요청 금액은 양수여야 합니다. amount=" + requestedAmount);
        }
        this.customer = customer;
        this.chargeRequestKey = chargeRequestKey;
        this.paymentMethod = paymentMethod;
        this.requestedAmount = requestedAmount;
        this.paymentProvider = paymentProvider;
        this.status = PointChargeStatus.PENDING;
        this.refundedAmount = 0L;
    }

    /** 충전 요청 생성. PENDING 상태로 시작한다. */
    public static PointCharge request(Member customer, String chargeRequestKey,
                                      PaymentMethod paymentMethod, long requestedAmount,
                                      String paymentProvider) {
        return new PointCharge(customer, chargeRequestKey, paymentMethod, requestedAmount,
                paymentProvider);
    }

    /**
     * PG 승인 응답을 받았다는 사실만 먼저 남긴다. <b>상태는 PENDING 그대로다</b>(#33).
     *
     * <p>승인 이후 잔액 반영이 실패해 트랜잭션이 롤백되면 승인 식별자가 함께 사라진다. 그러면
     * "돈은 나갔는데 우리는 모르는" 건이 되어 대사도 취소도 할 수 없다. 그래서 PG 응답 직후
     * <b>별도 트랜잭션</b>으로 이 값만 먼저 커밋한다.
     *
     * <p>{@code ck_point_charge_state_values} 는 금액·시각 컬럼만 검사하고 provider_payment_key 는
     * 보지 않으므로, PENDING 상태에서 이 값이 채워져 있어도 제약 위반이 아니다.
     *
     * <p>따라서 <b>{@code status = PENDING} 이면서 provider_payment_key 가 있는 행</b>은
     * "PG 승인은 받았는데 포인트 반영이 끝나지 않은 건"을 뜻한다. 대사 대상 추출 조건이 된다.
     * 이 상태의 건에 승인 요청이 다시 오면 PG 를 다시 부르면 안 된다 — 새 승인 식별자가 기존 값을
     * 덮어써 추적 근거가 사라진다.
     *
     * <p><b>한 번 기록된 식별자는 바뀌지 않는다.</b> 동시 승인 요청이 각각 PG 를 불러 서로 다른 승인을
     * 받아 오는 경우(벤더가 인증 토큰 기준으로 멱등하지 않을 때) 나중 값이 먼저 값을 덮으면, 먼저 받은
     * 승인이 어디에도 남지 않아 추적할 수 없게 된다. 그래서 덮어쓰지 않고 결과를 반환한다.
     *
     * @return 이번 호출로 기록했거나 이미 같은 값이면 {@code true}, 다른 값이 이미 기록돼 있으면
     *         {@code false}(= 같은 충전에 PG 승인이 둘 존재한다는 신호)
     */
    public boolean markApprovalReceived(String providerPaymentKey) {
        requireStatus(PointChargeStatus.PENDING, "승인 응답 기록");
        if (this.providerPaymentKey != null) {
            return this.providerPaymentKey.equals(providerPaymentKey);
        }
        this.providerPaymentKey = providerPaymentKey;
        return true;
    }

    /** 결제 승인: PENDING → PAID. 전액 승인이므로 approved_amount = requested_amount. */
    public void approve(String providerPaymentKey, String issuerCode, String maskedPaymentMethod) {
        requireStatus(PointChargeStatus.PENDING, "승인");
        this.status = PointChargeStatus.PAID;
        this.approvedAmount = this.requestedAmount;
        // markApprovalReceived 로 이미 기록된 식별자는 덮어쓰지 않는다 — 같은 이유다(위 주석 참조).
        if (this.providerPaymentKey == null) {
            this.providerPaymentKey = providerPaymentKey;
        }
        this.issuerCode = issuerCode;
        this.maskedPaymentMethod = maskedPaymentMethod;
        this.approvedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 결제 실패: PENDING → FAILED. */
    public void fail(String failureReason) {
        requireStatus(PointChargeStatus.PENDING, "실패 처리");
        this.status = PointChargeStatus.FAILED;
        this.failureReason = failureReason;
    }

    /**
     * 결제 취소: PENDING → CANCELED. (승인 전 사용자/시스템 취소, #34)
     *
     * <p>사유를 {@code failure_reason} 에 담는다 — CANCELED 전용 컬럼을 따로 두지 않았다.
     * {@code ck_point_charge_state_values} 는 금액·시각 컬럼만 검사하고 사유 컬럼은 상태와 묶지
     * 않으므로 제약 위반이 아니다. 대신 <b>이 컬럼 하나가 두 의미를 겸한다</b> — 값을 해석할 때는
     * 반드시 {@code status} 를 함께 봐야 한다(FAILED 면 PG 거절 사유, CANCELED 면 취소 사유).
     *
     * <p>{@code refund_reason} 은 PAID 이후 환불 전용이라 여기 쓰지 않는다. 승인 전 취소는 되돌릴
     * 결제가 없어 환불과 다른 사건이다.
     */
    public void cancel(String cancelReason) {
        requireStatus(PointChargeStatus.PENDING, "취소");
        this.status = PointChargeStatus.CANCELED;
        this.failureReason = cancelReason;
    }

    /** 전액 환불: PAID → REFUNDED. refunded_amount = approved_amount. */
    public void refund(String providerRefundKey, String refundReason) {
        requireStatus(PointChargeStatus.PAID, "환불");
        this.status = PointChargeStatus.REFUNDED;
        this.refundedAmount = this.approvedAmount;
        this.providerRefundKey = providerRefundKey;
        this.refundReason = refundReason;
        this.refundedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requireStatus(PointChargeStatus required, String action) {
        if (this.status != required) {
            throw new IllegalStateException(
                    action + " 불가: 현재 상태 " + status + " (요구 상태 " + required + ")");
        }
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.requestedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
