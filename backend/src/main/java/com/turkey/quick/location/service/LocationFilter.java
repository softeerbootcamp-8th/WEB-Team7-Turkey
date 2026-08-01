package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.time.Duration;

/**
 * 이전 최신 위치와 비교해 새 위치가 의미 있는 변화인지 판정한다(#82).
 *
 * <p>순수 함수다. 시각·정확도 판정({@link LocationAcceptancePolicy}, #234)과 측정 시각 단조성
 * 판정(#235)이 <b>먼저</b> 끝난 뒤에 호출된다. 즉 여기 들어오는 좌표는 이미 "값 자체는 믿을 수
 * 있고 이전보다 최신"이다. 이 클래스는 "믿을 수 있는 좌표가 의미 있게 움직였는가"만 본다.
 *
 * <p>판정은 저장 여부와 전송 여부를 각각 결정한다({@link LocationUpdateOutcome#shouldStore()},
 * {@link LocationUpdateOutcome#shouldPublish()}). {@code DUPLICATE} 만 "저장은 하되 전송하지
 * 않는" 값인데, 정지한 라이더도 Redis 키 TTL 을 갱신해야 배차 후보 검색(#83)에서 빠지지 않기
 * 때문이다.
 */
public final class LocationFilter {

    /**
     * 최소 이동 거리(미터). 정지 상태의 소비자용 GPS 흔들림이 통상 5~15m 라, 신호 대기 중인
     * 라이더가 계속 이벤트를 만들지 않게 하는 값이다. 프론트 1차 필터와 같은 값을 쓴다 —
     * 클라이언트가 통과시킨 좌표는 서버도 통과해야 두 필터가 서로 싸우지 않는다(#81 사람 확인).
     */
    private static final double MIN_MOVE_DISTANCE_METERS = 20;

    /**
     * 허용 최대 속도(m/s). 180km/h 로 오토바이 최고 속도의 두 배 이상이라 정상 주행을 거부하지
     * 않으면서, 수 km 단위의 측위 점프만 잡는다(#81 사람 확인).
     */
    private static final double MAX_SPEED_METERS_PER_SECOND = 50;

    /**
     * 속도 검사 유효 구간의 하한. 이보다 짧은 간격에서는 분모가 잡음이라 어떤 실제 이동도
     * 무한히 빠르게 보인다.
     */
    private static final Duration MIN_ELAPSED_FOR_SPEED_CHECK = Duration.ofSeconds(1);

    /**
     * 속도 검사 유효 구간의 상한(사람 확인, 2026-07-29). 이보다 오래 끊겼다면 앱이 백그라운드였거나
     * 터널을 지났다는 뜻이고, 그 사이의 큰 이동은 정상이다.
     *
     * <p>이 상한이 <b>기준선 오염을 저절로 복구</b>한다. 최초 좌표는 비교 대상이 없어 무조건
     * 통과하므로 잘못된 좌표가 기준이 될 수 있고, 그러면 이후 정상 좌표가 전부 이상 속도로 막힌다.
     * 거부된 좌표는 기준을 전진시키지 않으니 기준은 거부될 때마다 늙고, 이 상한을 넘으면 새 좌표가
     * 기준으로 교체된다. 연속 거부 횟수를 세는 카운터가 필요 없다 — 그런 상태를 JVM 에 두는 것은
     * 수평 확장에서 금지되고, Redis 에 두면 라이더마다 키가 하나 더 늘어난다.
     */
    private static final Duration MAX_ELAPSED_FOR_SPEED_CHECK = Duration.ofSeconds(30);

    /** 하버사인 공식의 지구 반지름(미터). */
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private LocationFilter() {
    }

    /**
     * @param previous 저장된 최신 위치. 없으면 null
     * @param next     새로 들어온 위치
     */
    public static LocationUpdateOutcome decide(RiderLocationSnapshot previous, RiderLocationSnapshot next) {
        if (previous == null) {
            return LocationUpdateOutcome.ACCEPTED;
        }

        double distanceMeters = haversineMeters(previous, next);
        Duration elapsed = Duration.between(previous.measuredAt(), next.measuredAt());

        if (exceedsSpeedLimit(distanceMeters, elapsed)) {
            return LocationUpdateOutcome.IMPLAUSIBLE_SPEED;
        }
        if (distanceMeters < MIN_MOVE_DISTANCE_METERS) {
            return LocationUpdateOutcome.DUPLICATE;
        }
        return LocationUpdateOutcome.ACCEPTED;
    }

    /**
     * 속도 검사는 경과 시간이 유효 구간 안일 때만 한다. 구간 밖이면 거리 기준만 적용된다.
     *
     * 경과가 0 이하인 경우(호출 순서가 잘못돼 단조성 검사를 건너뛴 경우)도 하한에 걸려 검사를
     * 건너뛴다 — 음수 분모로 엉뚱한 판정을 내지 않는다.
     */
    private static boolean exceedsSpeedLimit(double distanceMeters, Duration elapsed) {
        if (elapsed.compareTo(MIN_ELAPSED_FOR_SPEED_CHECK) < 0
                || elapsed.compareTo(MAX_ELAPSED_FOR_SPEED_CHECK) > 0) {
            return false;
        }
        double elapsedSeconds = elapsed.toNanos() / 1_000_000_000.0;
        return distanceMeters / elapsedSeconds > MAX_SPEED_METERS_PER_SECOND;
    }

    /**
     * 두 좌표 사이의 대권 거리(미터).
     *
     * 이 거리 규모에서는 평면 근사로도 충분하지만 하버사인을 쓴다 — 위도에 따라 달라지는 오차를
     * 따질 필요가 없고, 알려진 좌표쌍으로 검증하기 쉽다. 계산은 double 로 한다(좌표 자체는
     * BigDecimal 이지만 삼각함수에 정밀도가 필요하지 않다).
     */
    public static double haversineMeters(RiderLocationSnapshot from, RiderLocationSnapshot to) {
        double fromLatitude = Math.toRadians(from.latitude().doubleValue());
        double toLatitude = Math.toRadians(to.latitude().doubleValue());
        double latitudeDelta = toLatitude - fromLatitude;
        double longitudeDelta = Math.toRadians(
                to.longitude().doubleValue() - from.longitude().doubleValue());

        double halfChordSquared = square(Math.sin(latitudeDelta / 2))
                + Math.cos(fromLatitude) * Math.cos(toLatitude) * square(Math.sin(longitudeDelta / 2));

        // 부동소수 오차로 1을 넘으면 asin 이 NaN 이 된다.
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(halfChordSquared)));
    }

    private static double square(double value) {
        return value * value;
    }
}
