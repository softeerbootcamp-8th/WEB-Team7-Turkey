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
 * 주문 생성·완료 시점의 운임 산정 스냅샷.
 * 요금 정책(fare_policy)이 이후 바뀌더라도 주문 당시의 산정 근거(정책 버전·거리·단가 결과)를
 * 그대로 보존하는 불변(append-only) 레코드다. 주문당 ESTIMATE/FINAL 각 최대 1건이다.
 *
 * total_fare 는 base/distance/surcharge 의 합이어야 한다(DB ck_order_fare_total). 이 불변식을
 * 앱에서도 깨지 않도록, 팩토리에서 합을 직접 계산해 세팅한다 — 외부가 total 을 임의로 넣지 못한다.
 *
 * order/fare_policy 는 @ManyToOne(LAZY) 로 매핑한다(ItemTypeSurcharge→FarePolicy 와 동일 방침).
 * 스냅샷을 다룰 때 대부분 주문/정책 상세가 즉시 필요하지 않으므로 지연 로딩하고,
 * OSIV off 이므로 지연 접근은 서비스 계층에서 처리한다.
 */
@Entity
@Table(name = "order_fare_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderFareSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fare_snapshot_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private DeliveryOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fare_policy_id", updatable = false)
    private FarePolicy farePolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "fare_type", nullable = false, length = 10, updatable = false)
    private FareType fareType;

    /** 산정에 사용한 정책 버전 스냅샷. fare_policy.policy_version 을 값으로 복사해 보존한다. */
    @Column(name = "policy_version", nullable = false, length = 30, updatable = false)
    private String policyVersion;

    @Column(name = "calculation_distance_meters", nullable = false, updatable = false)
    private Integer calculationDistanceMeters;

    @Column(name = "base_fare", nullable = false, updatable = false)
    private Long baseFare;

    @Column(name = "distance_fare", nullable = false, updatable = false)
    private Long distanceFare;

    @Column(name = "item_surcharge", nullable = false, updatable = false)
    private Long itemSurcharge;

    @Column(name = "total_fare", nullable = false, updatable = false)
    private Long totalFare;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private LocalDateTime calculatedAt;

    private OrderFareSnapshot(DeliveryOrder order, FarePolicy farePolicy, FareType fareType,
                              String policyVersion, Integer calculationDistanceMeters,
                              Long baseFare, Long distanceFare, Long itemSurcharge) {
        // DB 제약(NOT NULL / ck_order_fare_distance / UNSIGNED)을 앱 레벨에서도 방어한다.
        if (order == null) {
            throw new IllegalArgumentException("order 는 필수입니다.");
        }
        if (farePolicy == null) {
            throw new IllegalArgumentException("farePolicy 는 필수입니다.");
        }
        if (fareType == null) {
            throw new IllegalArgumentException("fareType 는 필수입니다.");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion 은 비어 있을 수 없습니다.");
        }
        if (policyVersion.length() > 30) {
            throw new IllegalArgumentException("policyVersion 은 30자를 초과할 수 없습니다.");
        }
        if (calculationDistanceMeters == null || calculationDistanceMeters <= 0) {
            throw new IllegalArgumentException("calculationDistanceMeters 는 양수여야 합니다.");
        }
        requireNonNegative(baseFare, "baseFare");
        requireNonNegative(distanceFare, "distanceFare");
        requireNonNegative(itemSurcharge, "itemSurcharge");

        this.order = order;
        this.farePolicy = farePolicy;
        this.fareType = fareType;
        this.policyVersion = policyVersion;
        this.calculationDistanceMeters = calculationDistanceMeters;
        this.baseFare = baseFare;
        this.distanceFare = distanceFare;
        this.itemSurcharge = itemSurcharge;
        // total 은 외부 입력을 받지 않고 합으로 계산 → ck_order_fare_total 을 앱에서도 보장한다.
        this.totalFare = baseFare + distanceFare + itemSurcharge;
    }

    private static void requireNonNegative(Long amount, String field) {
        if (amount == null || amount < 0) {
            throw new IllegalArgumentException(field + " 는 0 이상이어야 합니다.");
        }
    }

    /**
     * 운임 스냅샷을 생성한다. total_fare 는 세 구성요소의 합으로 자동 계산된다.
     *
     * @param fareType ESTIMATE(예상) 또는 FINAL(최종)
     */
    public static OrderFareSnapshot create(DeliveryOrder order, FarePolicy farePolicy, FareType fareType,
                                           String policyVersion, Integer calculationDistanceMeters,
                                           Long baseFare, Long distanceFare, Long itemSurcharge) {
        return new OrderFareSnapshot(order, farePolicy, fareType, policyVersion,
                calculationDistanceMeters, baseFare, distanceFare, itemSurcharge);
    }

    /* 불변 이력이라 updated_at 이 없다. 생성 시점(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.calculatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
