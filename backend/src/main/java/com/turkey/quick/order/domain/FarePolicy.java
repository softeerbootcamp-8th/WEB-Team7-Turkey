package com.turkey.quick.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거리 단위당 요금을 직접 저장하는 요금 정책. 거리 구간별 규칙을 별도 행으로 관리하지 않는다.
 *
 * 정책은 policy_version 단위로 관리되며, 동시에 활성(ACTIVE)일 수 있는 정책은 최대 1개다 —
 * DB 의 active_policy_marker(VIRTUAL) + uk_fare_policy_active UNIQUE 가 이를 강제한다.
 * 이 엔터티는 전이 자체의 유효성만 검증하고, 기존 활성 정책을 먼저 비활성화하는 오케스트레이션은
 * 서비스 계층 책임이다.
 *
 * item_type_surcharge 는 특정 정책 버전 없이는 존재 의미가 없는 하위 구성요소이므로,
 * 이 루트의 addSurcharge() 를 통해서만 생성된다.
 */
@Entity
@Table(name = "fare_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FarePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fare_policy_id")
    private Long id;

    @Column(name = "policy_version", nullable = false, length = 30, updatable = false)
    private String policyVersion;

    @Column(name = "base_fare", nullable = false, updatable = false)
    private long baseFare;

    @Column(name = "distance_unit_meters", nullable = false, updatable = false)
    private int distanceUnitMeters;

    @Column(name = "distance_unit_fare", nullable = false, updatable = false)
    private long distanceUnitFare;

    @Column(name = "max_delivery_distance_meters", nullable = false, updatable = false)
    private int maxDeliveryDistanceMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FarePolicyStatus status;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "farePolicy", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ItemTypeSurcharge> surcharges = new ArrayList<>();

    private FarePolicy(String policyVersion, long baseFare, int distanceUnitMeters,
                       long distanceUnitFare, int maxDeliveryDistanceMeters,
                       LocalDateTime effectiveFrom) {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "정책 버전은 비어 있을 수 없습니다. policyVersion=" + policyVersion);
        }
        if (policyVersion.length() > 30) {
            throw new IllegalArgumentException(
                    "정책 버전은 30자를 초과할 수 없습니다. policyVersion=" + policyVersion);
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("적용 시작 시각은 필수입니다. effectiveFrom=null");
        }
        if (baseFare <= 0) {
            throw new IllegalArgumentException("기본요금은 양수여야 합니다. baseFare=" + baseFare);
        }
        if (distanceUnitMeters <= 0) {
            throw new IllegalArgumentException(
                    "거리 단위는 양수여야 합니다. distanceUnitMeters=" + distanceUnitMeters);
        }
        if (distanceUnitFare <= 0) {
            throw new IllegalArgumentException(
                    "거리 단가는 양수여야 합니다. distanceUnitFare=" + distanceUnitFare);
        }
        if (maxDeliveryDistanceMeters <= 0) {
            throw new IllegalArgumentException(
                    "최대 배송 거리는 양수여야 합니다. maxDeliveryDistanceMeters="
                            + maxDeliveryDistanceMeters);
        }
        this.policyVersion = policyVersion;
        this.baseFare = baseFare;
        this.distanceUnitMeters = distanceUnitMeters;
        this.distanceUnitFare = distanceUnitFare;
        this.maxDeliveryDistanceMeters = maxDeliveryDistanceMeters;
        this.effectiveFrom = effectiveFrom;
        this.status = FarePolicyStatus.INACTIVE;
    }

    /** 새 요금 정책 생성. 항상 INACTIVE 로 시작하고 activate() 로 명시적으로 활성화한다. */
    public static FarePolicy create(String policyVersion, long baseFare, int distanceUnitMeters,
                                    long distanceUnitFare, int maxDeliveryDistanceMeters,
                                    LocalDateTime effectiveFrom) {
        return new FarePolicy(policyVersion, baseFare, distanceUnitMeters, distanceUnitFare,
                maxDeliveryDistanceMeters, effectiveFrom);
    }

    /**
     * 물품 종류별 할증 추가. 같은 itemType 은 중복 등록할 수 없다 —
     * DB uk_item_surcharge_policy_type 과 동일한 조합을 앱에서도 먼저 막아
     * 제약 위반 예외 대신 명확한 도메인 예외로 실패시킨다.
     */
    public void addSurcharge(ItemType itemType, long surchargeAmount) {
        if (surchargeAmount < 0) {
            throw new IllegalArgumentException(
                    "할증 금액은 음수일 수 없습니다. surchargeAmount=" + surchargeAmount);
        }
        boolean duplicate = this.surcharges.stream()
                .anyMatch(surcharge -> surcharge.getItemType() == itemType);
        if (duplicate) {
            throw new IllegalStateException(
                    "이미 등록된 물품 종류입니다: " + itemType
                            + " (policyVersion=" + policyVersion + ")");
        }
        this.surcharges.add(ItemTypeSurcharge.create(this, itemType, surchargeAmount));
    }

    /** 정책 활성화: INACTIVE → ACTIVE. */
    public void activate() {
        requireStatus(FarePolicyStatus.INACTIVE, "활성화");
        this.status = FarePolicyStatus.ACTIVE;
    }

    /** 정책 비활성화: ACTIVE → INACTIVE. 적용 종료 시각을 남긴다. */
    public void deactivate() {
        requireStatus(FarePolicyStatus.ACTIVE, "비활성화");
        this.status = FarePolicyStatus.INACTIVE;
        this.effectiveTo = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requireStatus(FarePolicyStatus required, String action) {
        if (this.status != required) {
            throw new IllegalStateException(
                    action + " 불가: 현재 상태 " + status + " (요구 상태 " + required + ")");
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
