package com.turkey.quick.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 라이더 위치 갱신 요청.
 *
 * <p>라이더 식별자를 받지 않는다 — 세션에서 얻는다. 받으면 남의 위치를 지목할 수 있는 통로가
 * 된다({@code CustomerDeliveryApi} Javadoc 의 같은 원칙).
 *
 * <p>여기 붙은 Bean Validation 은 <b>400 으로 끊을 것만</b> 담는다. 위반은
 * {@code GlobalExceptionHandler} 를 타고 400 이 되기 때문이다. 그래서 정확도 <i>상한</i>(100m)은
 * 여기 {@code @DecimalMax} 로 달 수 없다 — 실내 측위에서 정상적으로 나오는 값이라 200 으로
 * 수용하고 버려야 한다. 상한 판정과 시각 판정은 {@code LocationAcceptancePolicy} 가 맡는다.
 * 반대로 정확도 <i>음수</i>는 물리적으로 불가능한 입력이라 여기서 끊는다.
 *
 * <p>좌표 범위는 {@code order/dto/AddressRequest} 와 같은 조건·같은 방식으로 검증한다.
 * {@code RiderLocationSnapshot} 과 DB CHECK 가 다시 확인하지만, 잘못된 입력을 컨트롤러 경계에서
 * 끊어 도메인까지 내려보내지 않기 위해 중복해서 둔다.
 *
 * <p>측정 시각을 {@code LocalDateTime} 이 아니라 {@link Instant} 로 받는 이유는
 * <b>시간대 표기를 강제하기 위해서</b>다. 실측한 Jackson 동작은 이렇다:
 *
 * <table border="1">
 *   <caption>같은 문자열을 두 타입으로 역직렬화한 결과</caption>
 *   <tr><th>입력</th><th>{@code LocalDateTime}</th><th>{@code Instant}</th></tr>
 *   <tr><td>{@code ...12:34:56.789Z}</td><td>통과</td><td>통과</td></tr>
 *   <tr><td>{@code ...21:34:56.789+09:00}</td><td>실패</td><td>통과(UTC 로 정규화)</td></tr>
 *   <tr><td>{@code ...12:34:56.789}</td><td><b>조용히 UTC 로 간주</b></td><td>실패</td></tr>
 * </table>
 *
 * <p>마지막 줄이 이 선택의 이유다. {@code LocalDateTime} 은 시간대 없는 문자열을 받아 UTC 라고
 * 단정한다. 클라이언트가 서울 로컬 시각을 시간대 없이 보내면 9시간 틀어진 값이 <b>오류 없이</b>
 * 들어오고, 그 뒤로 모든 위치가 STALE 로 판정돼 위치 갱신이 통째로 죽는다 — 원인을 찾기 가장
 * 어려운 형태의 실패다. {@code Instant} 는 시간대 표기가 없으면 400 으로 끊고, {@code +09:00}
 * 같은 오프셋도 UTC 로 정규화해 받는다.
 *
 * <p>저장소의 다른 시각 필드는 모두 {@code LocalDateTime} 이지만 전부 <b>응답</b>이고, 요청으로
 * 시각을 받는 선례는 없었다. 내부 저장 타입은 관례를 지켜 UTC {@code LocalDateTime} 으로 바꿔 넘긴다.
 */
@Schema(description = "라이더 현재 위치 갱신 요청")
public record RiderLocationUpdateRequest(

        @Schema(description = "위도", example = "37.4979")
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0276")
        @NotNull @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude,

        @Schema(description = "단말이 좌표를 측정한 시각(UTC, ISO-8601). 서버 저장 시각과 구분된다.",
                example = "2026-07-29T12:34:56.789Z")
        @NotNull
        Instant measuredAt,

        @Schema(description = "위치 정확도(미터). 단말이 제공하지 않으면 생략한다.", example = "12.5")
        @PositiveOrZero
        BigDecimal accuracyMeters
) {

    /** 저장·전파 계층이 쓰는 값 객체로 바꾼다. 자릿수 정규화는 스냅샷이 알아서 한다. */
    public RiderLocationSnapshot toSnapshot() {
        return new RiderLocationSnapshot(
                latitude,
                longitude,
                LocalDateTime.ofInstant(measuredAt, ZoneOffset.UTC),
                accuracyMeters);
    }
}
