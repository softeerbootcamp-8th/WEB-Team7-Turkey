package com.turkey.quick.order.domain;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.rider.domain.RiderProfile;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
 * 배송 요청 및 현재 배송 상태.
 *
 * 상태 흐름은 WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED 이고,
 * 취소는 배차 전(WAITING)에만 가능하다. 전이는 setter 가 아니라 아래 행위 메서드로만 일어나며,
 * 허용 여부 판정은 {@link OrderStatus#canTransitionTo}에 위임한다.
 *
 * 각 전이 메서드는 DDL CHECK 제약과 정확히 같은 필드 조합을 세팅한다(앱·DB 이중 방어):
 * - ck_delivery_assignment: assign() 이 assignedRider 와 assignedAt 을 함께 채운다.
 *   취소는 WAITING 에서만 가능하므로 CANCELED 행에는 라이더가 남지 않는다.
 * - ck_delivery_completed_at / ck_delivery_canceled_at: complete() / cancel() 만 해당 시각을 채운다.
 *
 * 주소·연락처·물품 종류는 주문 시점 값을 스냅샷으로 보관한다 — 회원 정보가 바뀌어도 과거 주문의
 * 픽업지·수령인은 그대로여야 하기 때문이다.
 *
 * 동시성: 낙관적 락(@Version)을 두지 않는다(팀 정책). "고객은 진행 중 배송요청 1건",
 * "라이더는 진행 중 배송 1건" 은 생성 컬럼 active_customer_id/active_rider_id 의 UNIQUE 가
 * DB 수준에서 강제하고, 배차 경쟁은 조건부 UPDATE(WHERE status='WAITING')로 판정한다.
 * 두 생성 컬럼은 DB 전용 제약이므로 엔터티에 필드로 매핑하지 않는다.
 *
 * 이 엔터티는 주문 쪽 상태만 바꾼다. 배차 확정 시 라이더를 BUSY 로 만드는 것처럼 여러 애그리거트를
 * 한 트랜잭션으로 묶는 오케스트레이션은 서비스 계층 책임이다(#143 범위 밖).
 */
@Entity
@Table(name = "delivery_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private Member customer;

    /** 배차 전에는 null. assign() 에서만 채워지고 이후 바뀌지 않는다(배차 포기는 MVP 범위 밖). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_rider_id")
    private RiderProfile assignedRider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    /** CHAR(36) 컬럼(UUID 문자열). (customer, request_key) 유니크로 동일 요청 재전송을 막는다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_key", nullable = false, length = 36, updatable = false)
    private String requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30, updatable = false)
    private ItemType itemType;

    /** 픽업지-도착지 직선거리. 요금 산정 기준값이라 주문 시점 값을 고정 보관한다. */
    @Column(name = "straight_distance_meters", nullable = false, updatable = false)
    private int straightDistanceMeters;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "roadAddress",
                    column = @Column(name = "pickup_road_address",
                            nullable = false, length = 255, updatable = false)),
            @AttributeOverride(name = "detailAddress",
                    column = @Column(name = "pickup_detail_address",
                            length = 255, updatable = false)),
            @AttributeOverride(name = "postalCode",
                    column = @Column(name = "pickup_postal_code",
                            nullable = false, length = 10, updatable = false)),
            @AttributeOverride(name = "latitude",
                    column = @Column(name = "pickup_latitude",
                            nullable = false, precision = 10, scale = 7, updatable = false)),
            @AttributeOverride(name = "longitude",
                    column = @Column(name = "pickup_longitude",
                            nullable = false, precision = 10, scale = 7, updatable = false))
    })
    private Address pickup;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "roadAddress",
                    column = @Column(name = "destination_road_address",
                            nullable = false, length = 255, updatable = false)),
            @AttributeOverride(name = "detailAddress",
                    column = @Column(name = "destination_detail_address",
                            length = 255, updatable = false)),
            @AttributeOverride(name = "postalCode",
                    column = @Column(name = "destination_postal_code",
                            nullable = false, length = 10, updatable = false)),
            @AttributeOverride(name = "latitude",
                    column = @Column(name = "destination_latitude",
                            nullable = false, precision = 10, scale = 7, updatable = false)),
            @AttributeOverride(name = "longitude",
                    column = @Column(name = "destination_longitude",
                            nullable = false, precision = 10, scale = 7, updatable = false))
    })
    private Address destination;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name",
                    column = @Column(name = "sender_name",
                            nullable = false, length = 50, updatable = false)),
            @AttributeOverride(name = "phoneNumber",
                    column = @Column(name = "sender_phone_number",
                            nullable = false, length = 20, updatable = false))
    })
    private Contact sender;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name",
                    column = @Column(name = "recipient_name",
                            nullable = false, length = 50, updatable = false)),
            @AttributeOverride(name = "phoneNumber",
                    column = @Column(name = "recipient_phone_number",
                            nullable = false, length = 20, updatable = false))
    })
    private Contact recipient;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "moving_to_pickup_at")
    private LocalDateTime movingToPickupAt;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "delivering_at")
    private LocalDateTime deliveringAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private DeliveryOrder(Member customer, String requestKey, ItemType itemType,
                          int straightDistanceMeters, Address pickup, Address destination,
                          Contact sender, Contact recipient) {
        if (customer == null) {
            throw new IllegalArgumentException("주문 고객은 필수입니다. customer=null");
        }
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException(
                    "요청 식별값은 비어 있을 수 없습니다. requestKey=" + requestKey);
        }
        if (itemType == null) {
            throw new IllegalArgumentException("물품 종류는 필수입니다. itemType=null");
        }
        if (straightDistanceMeters <= 0) {
            throw new IllegalArgumentException(
                    "직선거리는 양수여야 합니다. straightDistanceMeters=" + straightDistanceMeters);
        }
        if (pickup == null || destination == null) {
            throw new IllegalArgumentException("픽업지와 도착지는 필수입니다.");
        }
        if (sender == null || recipient == null) {
            throw new IllegalArgumentException("보내는 사람과 받는 사람 정보는 필수입니다.");
        }
        this.customer = customer;
        this.requestKey = requestKey;
        this.itemType = itemType;
        this.straightDistanceMeters = straightDistanceMeters;
        this.pickup = pickup;
        this.destination = destination;
        this.sender = sender;
        this.recipient = recipient;
        this.status = OrderStatus.WAITING;
    }

    /** 배송요청 생성. 항상 WAITING(배차 대기)으로 시작하고 라이더는 아직 없다. */
    public static DeliveryOrder request(Member customer, String requestKey, ItemType itemType,
                                        int straightDistanceMeters, Address pickup,
                                        Address destination, Contact sender, Contact recipient) {
        return new DeliveryOrder(customer, requestKey, itemType, straightDistanceMeters,
                pickup, destination, sender, recipient);
    }

    /**
     * 배차 확정: WAITING → ASSIGNED. 라이더와 배차 시각을 함께 채운다
     * (ck_delivery_assignment 가 요구하는 조합).
     * 라이더를 BUSY 로 바꾸는 것은 같은 트랜잭션 안에서 서비스 계층이 수행한다.
     */
    public void assign(RiderProfile rider) {
        if (rider == null) {
            throw new IllegalArgumentException("배차할 라이더는 필수입니다. rider=null");
        }
        transitionTo(OrderStatus.ASSIGNED);
        this.assignedRider = rider;
        this.assignedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 픽업지로 이동 시작: ASSIGNED → MOVING_TO_PICKUP. */
    public void startMovingToPickup() {
        transitionTo(OrderStatus.MOVING_TO_PICKUP);
        this.movingToPickupAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 물품 수령: MOVING_TO_PICKUP → PICKED_UP. */
    public void pickUp() {
        transitionTo(OrderStatus.PICKED_UP);
        this.pickedUpAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 도착지로 배송 시작: PICKED_UP → DELIVERING. */
    public void startDelivering() {
        transitionTo(OrderStatus.DELIVERING);
        this.deliveringAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 배송 완료: DELIVERING → COMPLETED.
     * 라이더 해제(BUSY→AVAILABLE)와 정산 생성은 같은 트랜잭션에서 서비스 계층이 수행한다.
     */
    public void complete() {
        transitionTo(OrderStatus.COMPLETED);
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 고객 취소: WAITING → CANCELED. 배차 이후에는 취소할 수 없다(MVP 범위).
     * 이 경로에서만 CANCELED 에 도달하므로 assignedRider/assignedAt 은 계속 null 로 남고,
     * ck_delivery_assignment 를 만족한다.
     */
    public void cancel(String reason) {
        transitionTo(OrderStatus.CANCELED);
        this.canceledAt = LocalDateTime.now(ZoneOffset.UTC);
        this.cancelReason = reason;
    }

    private void transitionTo(OrderStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "허용되지 않은 배송 상태 전이: " + status + " → " + target + " (orderId=" + id + ")");
        }
        this.status = target;
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
