package com.turkey.quick.location.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 라이더의 위치 한 지점. Redis 최신 위치의 값이자, 이후 SSE 이벤트·위치 이력(#102)으로
 * 흘러가는 공용 값 객체다.
 *
 * 좌표를 double 이 아니라 BigDecimal 로 두는 이유: 이 값이 최종적으로
 * {@link com.turkey.quick.location.domain.RiderLocationHistory} 의 DECIMAL(10,7) 컬럼으로
 * 들어간다. 중간에 double 을 거치면 소수 자릿수가 깨진다. 거리·속도 계산(#82)에서만
 * doubleValue() 로 내려 쓴다.
 *
 * 생성 시점에 DB 컬럼과 같은 소수 자릿수로 맞추는 이유는
 * {@link com.turkey.quick.location.domain.RiderLocationHistory} 와 같다 — 자릿수를 맞추지 않으면
 * INSERT 시 DB 가 반올림해 메모리 값과 저장 값이 달라진다. 여기서 미리 맞춰 두면 Redis 에
 * 기록되는 문자열도 같은 물리적 위치에 대해 항상 같은 형태가 된다.
 *
 * 측정 시각을 밀리초로 절단하는 것도 같은 이유다(#250). 요청은 {@code Instant} 로 들어와
 * 마이크로·나노초까지 담을 수 있는데, {@code rider_location_history.measured_at} 은
 * {@code DATETIME(3)} 이고 Redis 값 형식도 밀리초 3자리 고정 폭이다. 절단하지 않으면 저장한 값과
 * 다시 읽은 값이 같지 않아, <b>같은 요청을 두 번 보내면 두 번째가 "더 최신"으로 판정될 수 있다</b>
 * (조건부 갱신이 절단된 값끼리 비교하기 때문). 여기서 미리 잘라 두면 왕복이 정확히 맞는다.
 *
 * 좌표의 <b>범위</b> 검증(-90~90 등)은 여기서 하지 않는다 — 그건 요청 검증(#234)의 책임이다.
 * 이 record 는 값 객체로서의 구조적 불변식(필수 값 존재, 자릿수)만 지킨다.
 */
public record RiderLocationSnapshot(
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime measuredAt,
        BigDecimal accuracyMeters
) {

    /** DECIMAL(10,7) 의 소수 자릿수. */
    private static final int COORDINATE_SCALE = 7;

    /** DECIMAL(7,2) 의 소수 자릿수. */
    private static final int ACCURACY_SCALE = 2;

    public RiderLocationSnapshot {
        if (latitude == null) {
            throw new IllegalArgumentException("위도는 필수입니다. latitude=null");
        }
        if (longitude == null) {
            throw new IllegalArgumentException("경도는 필수입니다. longitude=null");
        }
        if (measuredAt == null) {
            throw new IllegalArgumentException("측정 시각은 필수입니다. measuredAt=null");
        }
        latitude = latitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        longitude = longitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        // 좌표는 반올림하지만 시각은 버린다 — 반올림하면 측정하지 않은 미래 시각이 만들어지고,
        // 미래 시각 판정(#234)이 이미 통과한 뒤라 그 값이 그대로 저장된다.
        measuredAt = measuredAt.truncatedTo(ChronoUnit.MILLIS);
        // 단말이 정확도를 제공하지 않으면 null 이다(엔터티 컬럼도 nullable).
        accuracyMeters = accuracyMeters == null
                ? null
                : accuracyMeters.setScale(ACCURACY_SCALE, RoundingMode.HALF_UP);
    }
}
