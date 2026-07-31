package com.turkey.quick.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문에 스냅샷으로 저장되는 주소(도로명 + 상세 + 우편번호 + 좌표).
 *
 * 별도 테이블이 아니라 delivery_order 의 컬럼 묶음(pickup_* / destination_*)으로 저장되므로
 * @Embeddable 로 매핑하고, 실제 컬럼명은 사용처에서 @AttributeOverride 로 지정한다.
 * 값 타입으로 묶어 두면 픽업지와 도착지를 인자로 넘길 때 위도/경도나 두 주소가 뒤바뀌어도
 * 컴파일이 통과해 버리는 사고를 막을 수 있고, 좌표 범위 검증도 한 곳에서 끝난다.
 *
 * 좌표 범위 검증은 DDL 의 ck_delivery_pickup_lat/lon, ck_delivery_dest_lat/lon 과 같은 조건이다
 * (앱·DB 이중 방어).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    /** DECIMAL(10,7) 의 소수 자릿수. 약 1cm 해상도로, 배송 좌표에 충분하다. */
    private static final int COORDINATE_SCALE = 7;

    @Column(nullable = false, length = 255)
    private String roadAddress;

    @Column(length = 255)
    private String detailAddress;

    @Column(nullable = false, length = 10)
    private String postalCode;

    @Column(nullable = false, precision = 10, scale = COORDINATE_SCALE)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = COORDINATE_SCALE)
    private BigDecimal longitude;

    private Address(String roadAddress, String detailAddress, String postalCode,
                    BigDecimal latitude, BigDecimal longitude) {
        requireText(roadAddress, "도로명 주소");
        requireText(postalCode, "우편번호");
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
        this.postalCode = postalCode;
        this.latitude = normalize(latitude, "위도", -90, 90);
        this.longitude = normalize(longitude, "경도", -180, 180);
    }

    /** 상세 주소는 선택 값이다(단독주택 등 상세 주소가 없는 경우). */
    public static Address of(String roadAddress, String detailAddress, String postalCode,
                             BigDecimal latitude, BigDecimal longitude) {
        return new Address(roadAddress, detailAddress, postalCode, latitude, longitude);
    }

    /**
     * 범위를 검증하고 DB 컬럼과 같은 소수 자릿수로 맞춘다.
     * 자릿수를 여기서 맞추지 않으면 INSERT 시 DB 가 반올림해, 같은 트랜잭션 안에서
     * 메모리의 엔터티 값과 실제 저장 값이 달라진다.
     */
    private BigDecimal normalize(BigDecimal value, String name, int min, int max) {
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

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다. " + name + "=" + value);
        }
    }
}
