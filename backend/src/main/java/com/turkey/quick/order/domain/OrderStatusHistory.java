package com.turkey.quick.order.domain;

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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 배송 상태 전이 불변(append-only) 이력.
 * 배송 상태가 바뀔 때마다 한 행씩 기록하는 로그로, 수정/삭제되지 않는다.
 *
 * 멱등성: (order, request_key) 유니크(uk_order_status_request)로 동일 전이 요청 재전송을 막는다.
 * 같은 request_key 로 재시도가 와도 한 건만 기록된다.
 *
 * 주체 규칙(ck_order_status_actor): CUSTOMER/RIDER 전이는 actor(회원)가 반드시 있고,
 * SYSTEM 전이(자동 처리)는 actor 가 없어야 한다. 이 쌍 조건을 팩토리에서도 검증한다.
 *
 * order 는 @ManyToOne(LAZY, 필수), actor 는 @ManyToOne(LAZY, 선택 — SYSTEM 이면 null)으로 매핑한다.
 */
@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "status_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private DeliveryOrder order;

    /** 전이 직전 상태. 최초 생성(WAITING 진입) 기록에서는 null 일 수 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30, updatable = false)
    private OrderStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30, updatable = false)
    private OrderStatus newStatus;

    /** 전이를 유발한 행위(예: REQUEST, ASSIGN, START_MOVING 등). DB CHECK 없이 자유 문자열. */
    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private String action;

    /** 전이 주체 회원. SYSTEM 전이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_member_id", updatable = false)
    private Member actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private ActorType actorType;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    /** CHAR(36) 멱등키(UUID 문자열). String 기본 매핑은 VARCHAR 라 CHAR 로 명시해 validate 를 맞춘다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_key", nullable = false, length = 36, updatable = false)
    private String requestKey;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    private OrderStatusHistory(DeliveryOrder order, OrderStatus previousStatus, OrderStatus newStatus,
                               String action, ActorType actorType, Member actor,
                               String reason, String requestKey) {
        if (order == null) {
            throw new IllegalArgumentException("order 는 필수입니다.");
        }
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus 는 필수입니다.");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 은 비어 있을 수 없습니다.");
        }
        if (action.length() > 40) {
            throw new IllegalArgumentException("action 은 40자를 초과할 수 없습니다.");
        }
        if (actorType == null) {
            throw new IllegalArgumentException("actorType 은 필수입니다.");
        }
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey 는 비어 있을 수 없습니다.");
        }
        if (requestKey.length() > 36) {
            throw new IllegalArgumentException("requestKey 는 36자를 초과할 수 없습니다.");
        }
        if (reason != null && reason.length() > 255) {
            throw new IllegalArgumentException("reason 은 255자를 초과할 수 없습니다.");
        }
        // ck_order_status_actor: SYSTEM ⟺ actor 없음, CUSTOMER/RIDER ⟺ actor 있음.
        if (actorType == ActorType.SYSTEM && actor != null) {
            throw new IllegalArgumentException("SYSTEM 전이는 actor 를 가질 수 없습니다.");
        }
        if (actorType != ActorType.SYSTEM && actor == null) {
            throw new IllegalArgumentException(actorType + " 전이는 actor 가 필요합니다.");
        }
        // ck_order_status_changed: 같은 상태로의 전이는 기록하지 않는다.
        if (previousStatus != null && previousStatus == newStatus) {
            throw new IllegalArgumentException("previousStatus 와 newStatus 가 같을 수 없습니다.");
        }

        this.order = order;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.action = action;
        this.actorType = actorType;
        this.actor = actor;
        this.reason = reason;
        this.requestKey = requestKey;
    }

    /**
     * 상태 전이 이력을 생성한다.
     *
     * @param previousStatus 전이 직전 상태(최초 기록이면 null 허용)
     * @param actor          전이 주체 회원(SYSTEM 이면 null)
     */
    public static OrderStatusHistory create(DeliveryOrder order, OrderStatus previousStatus,
                                            OrderStatus newStatus, String action, ActorType actorType,
                                            Member actor, String reason, String requestKey) {
        return new OrderStatusHistory(order, previousStatus, newStatus, action, actorType, actor,
                reason, requestKey);
    }

    /* 불변 이력이라 updated_at 이 없다. 생성 시점(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.changedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
