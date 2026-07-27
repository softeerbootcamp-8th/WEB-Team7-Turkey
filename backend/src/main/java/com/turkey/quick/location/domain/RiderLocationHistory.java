package com.turkey.quick.location.domain;

import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.rider.domain.RiderProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DELIVERING 구간의 선별된 실제 운행 위치.
 *
 * 불변(append-only) 레코드다 — 한 번 기록한 위치는 수정하지 않으므로 전이 메서드도 updated_at 도 없다.
 * 라이더의 "최신 위치"는 Redis 가 정본이고, 이 테이블에는 이력으로 남길 지점만 선별해 넣는다.
 * 선별 기준(시간·이동거리)과 "DELIVERING 구간에만 저장한다"는 삽입 정책은 서비스 계층이 지킨다 —
 * 다른 테이블(delivery_order.status)을 참조해야 해서 DB CHECK 으로 표현할 수 없기 때문이다.
 *
 * 이 엔터티가 현재 주문 상태를 직접 검사하지 않는 이유: measured_at 은 단말이 측정한 시각이고
 * stored_at 은 서버 저장 시각이라, DELIVERING 중에 측정된 좌표 배치가 배송 완료 직후에 도착할 수
 * 있다. 저장 시점 상태를 하드하게 요구하면 이런 정상적인 지연 도착 데이터가 버려진다.
 *
 * rider 는 인자로 받지 않고 주문의 배정 라이더에서 파생한다 — 주문과 라이더를 각각 받으면 서로
 * 다른 조합이 저장될 수 있는데, DDL 에는 둘의 일치를 강제할 제약이 없다.
 *
 * 멱등성: (order_id, measured_at) 유니크가 같은 좌표 배치의 재전송으로 중복 행이 생기는 것을 막는다.
 */
@Entity
@Table(name = "rider_location_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderLocationHistory {

    /** DECIMAL(10,7) 의 소수 자릿수. */
    private static final int COORDINATE_SCALE = 7;

    /** DECIMAL(7,2) 의 소수 자릿수. */
    private static final int ACCURACY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private DeliveryOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id", updatable = false)
    private RiderProfile rider;

    @Column(name = "latitude", nullable = false, precision = 10, scale = COORDINATE_SCALE,
            updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = COORDINATE_SCALE,
            updatable = false)
    private BigDecimal longitude;

    /** 단말이 보고한 위치 정확도(미터). 기기·환경에 따라 없을 수 있어 선택 값이다. */
    @Column(name = "accuracy_meters", precision = 7, scale = ACCURACY_SCALE, updatable = false)
    private BigDecimal accuracyMeters;

    /** 단말이 좌표를 측정한 시각. 서버 저장 시각(stored_at)과 구분한다. */
    @Column(name = "measured_at", nullable = false, updatable = false)
    private LocalDateTime measuredAt;

    @Column(name = "stored_at", nullable = false, updatable = false)
    private LocalDateTime storedAt;

    private RiderLocationHistory(DeliveryOrder order, BigDecimal latitude, BigDecimal longitude,
                                 BigDecimal accuracyMeters, LocalDateTime measuredAt) {
        if (order == null) {
            throw new IllegalArgumentException("위치를 기록할 주문은 필수입니다. order=null");
        }
        if (order.getAssignedRider() == null) {
            throw new IllegalArgumentException(
                    "배차되지 않은 주문에는 위치를 기록할 수 없습니다. orderStatus="
                            + order.getStatus());
        }
        if (measuredAt == null) {
            throw new IllegalArgumentException("측정 시각은 필수입니다. measuredAt=null");
        }
        this.order = order;
        this.rider = order.getAssignedRider();
        this.latitude = normalizeCoordinate(latitude, "위도", -90, 90);
        this.longitude = normalizeCoordinate(longitude, "경도", -180, 180);
        this.accuracyMeters = normalizeAccuracy(accuracyMeters);
        this.measuredAt = measuredAt;
    }

    /**
     * 운행 위치 1건 기록. 라이더는 주문의 배정 라이더에서 파생된다.
     * accuracyMeters 는 단말이 정확도를 제공하지 않으면 null 로 넘긴다.
     */
    public static RiderLocationHistory record(DeliveryOrder order, BigDecimal latitude,
                                              BigDecimal longitude, BigDecimal accuracyMeters,
                                              LocalDateTime measuredAt) {
        return new RiderLocationHistory(order, latitude, longitude, accuracyMeters, measuredAt);
    }

    /**
     * 범위를 검증하고 DB 컬럼과 같은 소수 자릿수로 맞춘다(ck_rider_location_lat/lon 과 동일 조건).
     * 자릿수를 맞추지 않으면 INSERT 시 DB 가 반올림해 메모리 값과 저장 값이 달라진다.
     */
    private BigDecimal normalizeCoordinate(BigDecimal value, String name, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(name + "는 필수입니다. " + name + "=null");
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0
                || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException(
                    name + "는 " + min + " 이상 " + max + " 이하여야 합니다. " + name + "=" + value);
        }
        return value.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    /** ck_rider_location_accuracy 와 동일 조건: null 이거나 0 이상. */
    private BigDecimal normalizeAccuracy(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("정확도는 음수일 수 없습니다. accuracyMeters=" + value);
        }
        return value.setScale(ACCURACY_SCALE, RoundingMode.HALF_UP);
    }

    /* 불변 이력이라 updated_at 이 없다. 서버 저장 시각(UTC)만 기록한다. */
    @PrePersist
    void onCreate() {
        this.storedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
