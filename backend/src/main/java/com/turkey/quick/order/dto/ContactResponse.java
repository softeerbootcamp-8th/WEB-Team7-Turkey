package com.turkey.quick.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주문에 스냅샷으로 저장된 연락처 응답.
 * 개인정보이므로 노출 범위를 화면 단위로 판단한다 — 배차 전 콜 목록/상세에는 담지 않고,
 * 배차가 확정된 뒤(라이더 진행 배송)와 주문 당사자인 고객에게만 내려준다.
 */
@Schema(description = "연락처(주문 시점 스냅샷)")
public record ContactResponse(

        @Schema(description = "이름", example = "김고객")
        String name,

        @Schema(description = "전화번호", example = "010-1234-5678")
        String phoneNumber
) {
}
