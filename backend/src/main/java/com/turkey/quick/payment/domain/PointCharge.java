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

    /** 결제 승인: PENDING → PAID. 전액 승인이므로 approved_amount = requested_amount. */
    public void approve(String providerPaymentKey, String issuerCode, String maskedPaymentMethod) {
        requireStatus(PointChargeStatus.PENDING, "승인");
        this.status = PointChargeStatus.PAID;
        this.approvedAmount = this.requestedAmount;
        this.providerPaymentKey = providerPaymentKey;
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

    /** 결제 취소: PENDING → CANCELED. (승인 전 사용자/시스템 취소) */
    public void cancel() {
        requireStatus(PointChargeStatus.PENDING, "취소");
        this.status = PointChargeStatus.CANCELED;
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
