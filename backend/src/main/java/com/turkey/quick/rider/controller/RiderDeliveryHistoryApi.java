package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryHistoryListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 라이더 운행 기록 API 계약(인터페이스 전용, 구현 없음).
 *
 * <p>완료되어 정산이 생성된 배송만 다루는 읽기 전용 계약이다.
 *
 * <p>포인트 잔액·출금(초안 문서 §3의 {@code /api/rider/points/*})은 여기 없다 —
 * 지갑과 원장은 payment 도메인 소관이라 이 인터페이스에 섞지 않는다. 다만 출금 요청 엔터티
 * (rider_withdrawal)는 rider 도메인에 있으므로, 출금 API 를 어느 패키지에 둘지는 별도 결정이 필요하다.
 */
@Tag(name = "rider-history", description = "라이더 운행 기록 — 완료 배송 목록·상세")
@RequestMapping("/api/rider/history")
public interface RiderDeliveryHistoryApi {

    @Operation(summary = "운행 기록 목록",
            description = "완료된 배송을 최신순으로 조회한다. 화면 상단의 주간 정산 합계를 같은 응답에 담는다.")
    @GetMapping
    ApiResponse<RiderDeliveryHistoryListResponse> getDeliveryHistories(
            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "운행 기록 상세",
            description = "최종 운임 분해와 정산 금액, 상태 타임라인, 배송 완료 인증을 조회한다.")
    @GetMapping("/{deliveryId}")
    ApiResponse<RiderDeliveryHistoryDetailResponse> getDeliveryHistory(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId);
}
