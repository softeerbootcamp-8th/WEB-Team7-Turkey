package com.turkey.quick.payment.domain;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.rider.domain.RiderProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완료 주문당 라이더 정산.
 *
 * 별도의 정산 상태를 두지 않는다 — <b>행의 존재 자체가 완료된 정산</b>을 의미한다.
 * 성공하면 INSERT 되고, 실패하면 배송 완료 트랜잭션 전체가 롤백되어 행이 남지 않는다.
 * 따라서 이 엔터티에는 전이 메서드도 updated_at 도 없다(불변 레코드).
 *
 * 정산 근거는 반드시 <b>같은 주문의 FINAL 운임 스냅샷</b>이어야 한다. DB 는 이를
 * (order_id, final_fare_snapshot_id) 복합 FK 로 강제하고(단독 FK 였다면 A 주문의 정산이 B 주문의
 * 스냅샷을 가리키는 조합을 막지 못한다), 이 엔터티는 스냅샷 하나만 받아 주문을 거기서 파생시켜
 * 애초에 어긋난 조합을 만들 수 없게 한다. FINAL 여부도 팩토리에서 검증한다 — ESTIMATE 로는
 * 정산하지 않는다(예상 요금과 최종 요금의 차이를 허용하기로 한 정책의 반대편 보호 장치).
 *
 * 매핑 주의: order_id 컬럼은 복합 FK 의 일부이자 fk_rider_settlement_order 의 대상이기도 하다.
 * 한 프로퍼티 안에서 insertable 여부를 섞을 수 없으므로, 쓰기는 finalFareSnapshot 연관이 담당하고
 * order 연관은 같은 컬럼을 읽기 전용으로 바라본다(insertable/updatable=false).
 * 새로 만든 인스턴스에서도 getOrder() 가 비지 않도록 생성자에서 함께 채워 둔다.
 */
@Entity
@Table(name = "rider_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long id;

    /**
     * 정산 근거가 된 FINAL 운임 스냅샷. order_id 와 final_fare_snapshot_id 를 함께 쓰는
     * 복합 FK(fk_rider_settlement_fare) 이며, 이 연관이 두 컬럼의 쓰기를 담당한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "order_id", referencedColumnName = "order_id",
                    updatable = false),
            @JoinColumn(name = "final_fare_snapshot_id", referencedColumnName = "fare_snapshot_id",
                    updatable = false)
    })
    private OrderFareSnapshot finalFareSnapshot;

    /** 위 복합 FK 가 쓰는 order_id 컬럼을 읽기 전용으로 바라보는 연관. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    private DeliveryOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id", updatable = false)
    private RiderProfile rider;

    @Column(name = "settlement_amount", nullable = false, updatable = false)
    private long settlementAmount;

    @Column(name = "settled_at", nullable = false, updatable = false)
    private LocalDateTime settledAt;

    private RiderSettlement(OrderFareSnapshot finalFareSnapshot, long settlementAmount) {
        if (finalFareSnapshot == null) {
            throw new IllegalArgumentException("정산 근거가 될 운임 스냅샷은 필수입니다. finalFareSnapshot=null");
        }
        if (finalFareSnapshot.getFareType() != FareType.FINAL) {
            throw new IllegalArgumentException(
                    "정산은 FINAL 운임 스냅샷으로만 할 수 있습니다. fareType="
                            + finalFareSnapshot.getFareType());
        }
        DeliveryOrder order = finalFareSnapshot.getOrder();
        if (order == null) {
            throw new IllegalArgumentException("운임 스냅샷에 주문이 없습니다. order=null");
        }
        if (order.getAssignedRider() == null) {
            throw new IllegalArgumentException(
                    "배차되지 않은 주문은 정산할 수 없습니다. orderStatus=" + order.getStatus());
        }
        if (settlementAmount <= 0) {
            throw new IllegalArgumentException(
                    "정산 금액은 양수여야 합니다. settlementAmount=" + settlementAmount);
        }
        this.finalFareSnapshot = finalFareSnapshot;
        this.order = order;
        this.rider = order.getAssignedRider();
        this.settlementAmount = settlementAmount;
    }

    /**
     * 배송 완료 시점의 정산 생성. 주문과 라이더는 스냅샷에서 파생되므로 어긋난 조합이 만들어지지 않는다.
     * 정산 금액을 별도로 받는 이유: 수수료 정책이 생기면 총 운임과 라이더 정산액이 갈릴 수 있어,
     * "총 운임 = 정산액"을 엔터티에 못박지 않는다. 현재 정책상 같은 값을 넘기면 된다.
     */
    public static RiderSettlement settle(OrderFareSnapshot finalFareSnapshot,
                                         long settlementAmount) {
        return new RiderSettlement(finalFareSnapshot, settlementAmount);
    }

    /* 행의 존재가 곧 완료된 정산이라 상태 컬럼도 updated_at 도 없다. 생성 시각(UTC)만 남긴다. */
    @PrePersist
    void onCreate() {
        this.settledAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
