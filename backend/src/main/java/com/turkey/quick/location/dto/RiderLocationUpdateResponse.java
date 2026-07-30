package com.turkey.quick.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 라이더 위치 갱신 결과.
 *
 * <p>{@code applied} 와 {@code published} 를 나눠 두는 이유: 중복·이상 이동 필터(#82)가
 * "저장은 하되 전파하지 않는" 경우를 만든다. 정지한 라이더도 Redis 키의 TTL 은 갱신해야
 * 배차 후보에서 빠지지 않지만, 고객 지도에 같은 좌표를 다시 밀어 줄 이유는 없다. 두 값을 하나로
 * 합치면 그 상태를 표현할 수 없다.
 *
 * <p>{@code published} 는 SSE 발행(#78)이 붙기 전까지 항상 false 다.
 *
 * <p>팩토리를 판정값 하나로 통일한 이유: {@code applied} 를 호출자가 직접 정하게 두면
 * {@code applied=false} 인데 {@code reason=ACCEPTED} 같은 조합이 만들어질 수 있다. 저장 여부는
 * 판정값이 이미 알고 있으므로({@link LocationUpdateOutcome#shouldStore()}) 거기서 파생시킨다.
 */
@Schema(description = "라이더 현재 위치 갱신 결과")
public record RiderLocationUpdateResponse(

        @Schema(description = "최신 위치가 갱신됐는지", example = "true")
        boolean applied,

        @Schema(description = "구독 중인 고객에게 전송됐는지", example = "false")
        boolean published,

        @Schema(description = "판정 사유")
        LocationUpdateOutcome reason
) {

    /**
     * @param outcome   판정값. {@code applied} 는 여기서 파생된다
     * @param published 실제로 구독자에게 전송했는지. 전송 대상이 아닌 판정에 true 를 줄 수 없다
     */
    public static RiderLocationUpdateResponse of(LocationUpdateOutcome outcome, boolean published) {
        if (published && !outcome.shouldPublish()) {
            throw new IllegalArgumentException(
                    "전송 대상이 아닌 판정을 전송했다고 응답할 수 없습니다. reason=" + outcome);
        }
        return new RiderLocationUpdateResponse(outcome.shouldStore(), published, outcome);
    }
}
