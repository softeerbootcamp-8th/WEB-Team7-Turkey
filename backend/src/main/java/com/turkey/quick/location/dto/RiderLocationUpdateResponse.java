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

    /** 검증을 통과해 최신 위치를 갱신했다. */
    public static RiderLocationUpdateResponse accept(boolean published) {
        return new RiderLocationUpdateResponse(true, published, LocationUpdateOutcome.ACCEPTED);
    }

    /** 요청은 정상이지만 쓸 수 없는 값이라 버렸다. 버린 값을 전파하는 경우는 없다. */
    public static RiderLocationUpdateResponse discard(LocationUpdateOutcome reason) {
        if (reason.isAccepted()) {
            throw new IllegalArgumentException("폐기 사유가 ACCEPTED 일 수 없습니다.");
        }
        return new RiderLocationUpdateResponse(false, false, reason);
    }
}
