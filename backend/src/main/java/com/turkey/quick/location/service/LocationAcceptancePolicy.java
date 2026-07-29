package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 측정 시각·정확도 기준으로 위치 갱신 요청을 받아들일지 판정한다.
 *
 * <p>Bean Validation 이 못 하는 두 판정을 맡는다. 시각 판정은 <b>현재 시각과 비교해야</b> 하므로
 * 표준 어노테이션으로 표현할 수 없고({@code @PastOrPresent} 는 시계 오차를 허용하지 않는다),
 * 정확도 상한은 <b>400 이 되어서는 안 되므로</b> 어노테이션으로 달 수 없다.
 *
 * <p>순수 함수다. {@code now} 를 인자로 받아 시계를 목킹하지 않고도 경계값을 테스트할 수 있게
 * 한다 — 5초·60초 경계가 이 클래스에서 유일하게 틀릴 수 있는 지점이다.
 *
 * <p>판정 순서는 미래 → 오래됨 → 정확도다. 사유는 하나만 응답에 담기므로 순서를 고정해 둔다.
 * 미래를 먼저 보는 이유는 그것만 400 이고, 시계가 틀린 단말의 값으로 나머지 판정을 해도 의미가
 * 없기 때문이다.
 */
public final class LocationAcceptancePolicy {

    /**
     * 미래 시각 허용 오차. 단말과 서버의 시계는 조금씩 어긋나므로 약간의 미래는 정상이다.
     * 이 범위를 넘으면 단말 시계가 실제로 틀렸다는 뜻이고, 그 값을 받아 최신 위치로 저장하면
     * #82 의 시각 단조성 판정이 막혀 이후 정상 좌표가 전부 버려진다. 그래서 400 으로 끊는다.
     */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(5);

    /**
     * 측정 시각 허용 과거. 1분 넘은 좌표는 "현재 위치"가 아니다. 프론트도 같은 기준으로 1차
     * 필터링하지만(#196 확정), 탭 복귀 직후 캐시된 fix 나 네트워크 지연으로 정상적으로 도달할 수
     * 있어 400 이 아니라 폐기로 처리한다.
     */
    private static final Duration MAX_FIX_AGE = Duration.ofSeconds(60);

    /**
     * 정확도 상한(미터). 실내·지하의 Wi-Fi 측위는 100m 이상을 돌려주는데, 그런 좌표는 고객
     * 지도를 튀게 하고 배차 거리 계산을 틀어지게 한다. 정상적으로 발생하는 환경 조건이므로
     * 거부가 아니라 폐기다.
     */
    private static final BigDecimal MAX_ACCURACY_METERS = new BigDecimal("100");

    private LocationAcceptancePolicy() {
    }

    /**
     * @param now 판정 기준 시각. 호출자가 {@code Instant.now()} 를 넘긴다.
     * @return 수용 여부와 사유. 200 으로 응답되는 결과만 반환한다.
     * @throws IllegalArgumentException 측정 시각이 허용 오차를 넘어 미래인 경우(400)
     */
    public static LocationUpdateOutcome evaluate(RiderLocationUpdateRequest request, Instant now) {
        Instant measuredAt = request.measuredAt();

        if (measuredAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new IllegalArgumentException("측정 시각이 미래입니다. 단말 시계를 확인해 주세요.");
        }
        if (measuredAt.isBefore(now.minus(MAX_FIX_AGE))) {
            return LocationUpdateOutcome.STALE;
        }
        if (exceedsAccuracyLimit(request.accuracyMeters())) {
            return LocationUpdateOutcome.LOW_ACCURACY;
        }
        return LocationUpdateOutcome.ACCEPTED;
    }

    /** 정확도를 주지 않는 단말도 있으므로 null 은 판정하지 않는다(엔터티 컬럼도 nullable). */
    private static boolean exceedsAccuracyLimit(BigDecimal accuracyMeters) {
        return accuracyMeters != null && accuracyMeters.compareTo(MAX_ACCURACY_METERS) > 0;
    }
}
