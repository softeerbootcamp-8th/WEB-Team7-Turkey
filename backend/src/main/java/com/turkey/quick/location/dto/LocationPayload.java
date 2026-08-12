package com.turkey.quick.location.dto;

import com.turkey.quick.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * SSE 팬아웃 프레임의 {@code type} 판별 필드는 이 레코드가 위치 프레임임을 알린다(#398, Discussion
 * #375). {@code type}을 받지 않는 4-인자 생성자를 남겨 둔 이유: 이 레코드는 SSE 팬아웃 JSON뿐 아니라
 * {@code RiderLocationRepository}의 쉼표 구분 Redis 저장 형식에도 쓰이는데, 그쪽 {@code encode}는
 * 이 4개 필드만 <b>접근자 이름으로</b> 읽는다 — 필드를 추가해도 기존 호출부(생성 코드·테스트)를
 * 전혀 건드리지 않는다.
 *
 * <p><b>{@code status} 가 nullable 인 이유</b>(#449): 이 레코드는 두 경로에서 쓰인다.
 * <ul>
 *   <li><b>SSE 팬아웃</b> — {@code RiderLocationService} 가 {@link #withStatus} 로 상태를 채워
 *       발행한다. 주기적으로 흐르는 이 프레임에 상태를 실어, 일회성인 상태 전이 이벤트가
 *       유실돼도 <b>다음 위치(최대 5초)로 화면이 복구</b>되게 하는 것이 목적이다.</li>
 *   <li><b>Redis 최신 위치 저장·조회</b> — {@code RiderLocationRepository} 의 쉼표 구분 형식에는
 *       상태를 넣지 않는다(위치 저장소이지 주문 상태 저장소가 아니다). 그래서 {@code decode} 가
 *       만든 값은 상태가 {@code null} 이고, 그 값을 쓰는 폴링 응답
 *       ({@code CustomerDeliveryLocationResponse})은 상태를 <b>바깥 필드로 따로</b> 갖는다.</li>
 * </ul>
 * 즉 {@code null} 은 "상태를 모른다"가 아니라 <b>"이 경로에서는 상태가 이 필드의 관심사가
 * 아니다"</b> 는 뜻이다.
 */
public record LocationPayload(
        @Schema(description = "프레임 판별자, 항상 \"location\"", example = "location")
        String type,

        @Schema(description = "발행 시점의 배송 상태. SSE 팬아웃에서만 채워지고, "
                + "Redis 최신 위치에서 복원한 값에는 없다(null)", example = "DELIVERING")
        OrderStatus status,

        @Schema(description = "위도", example = "37.4979")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0276")
        BigDecimal longitude,

        @Schema(description = "측정 시각(UTC)", example = "2026-08-03T01:02:03.456Z")
        Instant measuredAt,

        @Schema(description = "측정 정확도(미터). 없으면 null", example = "12.5")
        BigDecimal accuracyMeters
) {
    public LocationPayload(BigDecimal latitude, BigDecimal longitude, Instant measuredAt, BigDecimal accuracyMeters) {
        this("location", null, latitude, longitude, measuredAt, accuracyMeters);
    }

    /**
     * 상태를 채운 사본을 만든다. 저장용 원본은 그대로 두고 <b>팬아웃 프레임만</b> 상태를 싣기
     * 위한 것이다 — {@code RiderLocationService} 가 같은 위치 값을 저장소와 팬아웃 양쪽에
     * 넘기는데, 저장 쪽에 상태가 섞이면 Redis 값 형식이 바뀐다.
     */
    public LocationPayload withStatus(OrderStatus status) {
        return new LocationPayload(type, status, latitude, longitude, measuredAt, accuracyMeters);
    }
}
