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
 * <p><b>{@code published} 의 의미를 정확히 읽어야 한다</b>(#78). "고객에게 도달했다"가 아니라
 * <b>"이 위치를 팬아웃 채널로 발행했다"</b>는 뜻이다. 전달은 at-most-once 라 도달 여부는 알 수
 * 없고 알 필요도 없다 — 유실은 다음 이벤트(BUSY 5초 주기)와 재연결 스냅샷(#79)이 복구한다.
 * <b>고객이 추적 화면을 보고 있는지도 알려주지 않는다.</b>
 *
 * <p>false 가 되는 경우는 셋이다: 판정이 전파 대상이 아니거나(정지한 라이더의 {@code DUPLICATE} 등),
 * 라이더에게 진행 중인 배송이 없거나(배차 전·완료 후 — {@code AVAILABLE} 라이더의 30초 주기 위치가
 * 대부분 이 경우다), 발행 자체가 실패했거나. <b>모두 정상 응답(200)이다.</b>
 *
 * <p>팩토리를 판정값 하나로 통일한 이유: {@code applied} 를 호출자가 직접 정하게 두면
 * {@code applied=false} 인데 {@code reason=ACCEPTED} 같은 조합이 만들어질 수 있다. 저장 여부는
 * 판정값이 이미 알고 있으므로({@link LocationUpdateOutcome#shouldStore()}) 거기서 파생시킨다.
 */
@Schema(description = "라이더 현재 위치 갱신 결과")
public record RiderLocationUpdateResponse(

        @Schema(description = "최신 위치가 갱신됐는지", example = "true")
        boolean applied,

        @Schema(description = "이 위치를 실시간 추적 채널로 발행했는지. 고객 도달이나 "
                + "구독 여부를 뜻하지 않는다(at-most-once).", example = "false")
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
