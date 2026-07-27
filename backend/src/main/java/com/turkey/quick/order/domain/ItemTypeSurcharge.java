package com.turkey.quick.order.domain;

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
 * 요금 정책 버전별 물품 종류 할증.
 * FarePolicy 없이는 존재 의미가 없는 애그리거트 하위 구성요소이므로 생성 경로를
 * package-private 으로 제한하고, FarePolicy.addSurcharge() 를 통해서만 만들어진다.
 */
@Entity
@Table(name = "item_type_surcharge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemTypeSurcharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_type_surcharge_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fare_policy_id", updatable = false)
    private FarePolicy farePolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30, updatable = false)
    private ItemType itemType;

    @Column(name = "surcharge_amount", nullable = false, updatable = false)
    private long surchargeAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ItemTypeSurcharge(FarePolicy farePolicy, ItemType itemType, long surchargeAmount) {
        this.farePolicy = farePolicy;
        this.itemType = itemType;
        this.surchargeAmount = surchargeAmount;
    }

    static ItemTypeSurcharge create(FarePolicy farePolicy, ItemType itemType,
                                    long surchargeAmount) {
        return new ItemTypeSurcharge(farePolicy, itemType, surchargeAmount);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
