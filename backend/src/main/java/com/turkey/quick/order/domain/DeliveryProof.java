package com.turkey.quick.order.domain;

import com.turkey.quick.rider.domain.RiderProfile;
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

/**
 * 주문당 배송 완료 인증.
 * 라이더가 배송을 완료할 때 남기는 인증 기록으로, 주문당 최대 1건이다(uk_delivery_proof_order).
 * 생성 후 변경되지 않는 불변(append-only) 레코드다.
 *
 * 실제 이미지 바이너리는 저장하지 않는다 — proof_value 에 참조값(사진 URL/스토리지 키,
 * 수령인 확인 참조, 인증코드)만 보관한다.
 *
 * order 는 delivery_order, rider 는 rider_profile 로의 FK다. 둘 다 @ManyToOne(LAZY, 필수)로 매핑한다.
 */
@Entity
@Table(name = "delivery_proof")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_proof_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private DeliveryOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id", updatable = false)
    private RiderProfile rider;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_type", nullable = false, length = 30, updatable = false)
    private ProofType proofType;

    /** 인증 참조값(사진 URL/키, 수령인 확인 참조, 인증코드). 이미지 바이너리는 담지 않는다. */
    @Column(name = "proof_value", nullable = false, length = 500, updatable = false)
    private String proofValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private DeliveryProof(DeliveryOrder order, RiderProfile rider,
                          ProofType proofType, String proofValue) {
        if (order == null) {
            throw new IllegalArgumentException("order 는 필수입니다.");
        }
        if (rider == null) {
            throw new IllegalArgumentException("rider 는 필수입니다.");
        }
        if (proofType == null) {
            throw new IllegalArgumentException("proofType 은 필수입니다.");
        }
        if (proofValue == null || proofValue.isBlank()) {
            throw new IllegalArgumentException("proofValue 는 비어 있을 수 없습니다.");
        }
        if (proofValue.length() > 500) {
            throw new IllegalArgumentException("proofValue 는 500자를 초과할 수 없습니다.");
        }

        this.order = order;
        this.rider = rider;
        this.proofType = proofType;
        this.proofValue = proofValue;
    }

    /**
     * 배송 완료 인증을 생성한다.
     *
     * @param proofValue 인증 참조값(이미지 바이너리가 아니라 URL/키/코드 등)
     */
    public static DeliveryProof create(DeliveryOrder order, RiderProfile rider,
                                       ProofType proofType, String proofValue) {
        return new DeliveryProof(order, rider, proofType, proofValue);
    }

    /* 불변 이력이라 updated_at 이 없다. 생성 시점(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
