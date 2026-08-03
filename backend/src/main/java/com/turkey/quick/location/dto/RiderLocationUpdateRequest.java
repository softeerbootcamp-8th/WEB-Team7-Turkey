package com.turkey.quick.location.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 라이더 위치 갱신 요청. <b>위치 정보만 받는다.</b>
 *
 * <p>배송 식별자를 받지 않는 이유가 두 가지다.
 * <ul>
 *   <li><b>클라이언트가 알 수 없다.</b> AVAILABLE 라이더는 어떤 배송도 맡고 있지 않아 채워 넣을 값이
 *       없다. 예전에는 이 필드가 {@code @NotNull} 이라 안드로이드 클라이언트
 *       ({@code mobile/android/.../RiderLocationService})의 위치 전송이 전부 400 이었다 — 그
 *       클라이언트는 좌표·시각·정확도만 보낸다.</li>
 *   <li><b>받으면 보안 구멍이다.</b> 라이더가 그 배송에 배정됐는지 검증하지 않으므로, 아무 배송 id
 *       나 넣으면 남의 고객 화면에 자기 위치를 흘려보낼 수 있었다(#291 에서 남아 있던 구멍).</li>
 * </ul>
 * 이제 서버가 {@code RiderLocationService} 에서 "이 라이더가 수행 중인 배송"을 DB 로 직접 풀어
 * 채널을 정한다 — 라이더가 자기 배송 외의 채널로 발행할 방법이 없다.
 */
public record RiderLocationUpdateRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,

        @NotNull @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude,

        @NotNull
        Instant measuredAt,

        @PositiveOrZero
        BigDecimal accuracyMeters
) {
    public LocationPayload toLocationPayload() {
        return new LocationPayload(latitude, longitude, measuredAt, accuracyMeters);
    }
}
