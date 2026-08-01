package com.turkey.quick.payment.dto;

import com.turkey.quick.payment.domain.PointChargeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 포인트 충전 취소 결과(CANCELED, #34).
 *
 * <p>{@link PointChargeConfirmResponse} 를 재사용하지 않았다. 취소에는 승인 금액도 승인 시각도
 * 존재하지 않아 그 두 필드가 영구히 0/null 로 남고, 그 모양이 그대로 프론트 타입이 된다
 * (Orval 이 스펙에서 훅과 타입을 생성한다). 계약에 "항상 비어 있는 필드"를 남기지 않는 편이 낫다.
 *
 * <p><b>balance 를 함께 돌려주는 이유</b>: 이 API 의 성공 조건이 "요청이 종료되고 <b>잔액은 변하지
 * 않는다</b>"이기 때문이다. 화면이 잔액을 다시 조회하지 않아도 되고, 승인 응답과 같은 모양이라
 * 두 경로를 같은 방식으로 다룰 수 있다.
 */
@Schema(description = "포인트 충전 취소 결과")
public record PointChargeCancelResponse(

        @Schema(description = "충전 식별자", example = "331")
        Long pointChargeId,

        @Schema(description = "충전 상태. 취소 성공 시 CANCELED.", example = "CANCELED")
        PointChargeStatus status,

        @Schema(description = "취소된 충전 요청 금액. 승인된 적이 없으므로 결제되지 않은 금액이다.",
                example = "10000")
        long requestedAmount,

        @Schema(description = "현재 잔액. 취소는 잔액을 바꾸지 않으므로 취소 전 값과 같다.",
                example = "22500")
        long balance,

        @Schema(description = "취소 사유. 서버가 채운다.", example = "고객이 결제를 취소했습니다.")
        String cancelReason
) {
}
