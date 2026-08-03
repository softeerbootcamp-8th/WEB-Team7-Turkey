package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.location.dto.CustomerDeliveryLocationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 고객 위치 폴링 API 계약(문서 전용, #311).
 *
 * <p>SSE({@code /tracking/stream})를 대체하지 않는다. 부하테스트로 두 방식의 용량을 비교하기 위해
 * 나란히 두는 실험용 arm이다(관련: #259, #270). SSE가 정본이라는 CLAUDE.md의 확정 결정은 바뀌지
 * 않는다.
 *
 * <p>인가·소유권 판정은 {@code /tracking} 스냅샷·SSE 스트림과 완전히 같다
 * ({@code DeliveryTrackingAccessService.authorizeTracking} 재사용) — 세 경로가 같은 404/409
 * 시맨틱이어야 부하테스트에서 SSE와 폴링을 공정하게 비교할 수 있다.
 *
 * <p>매핑({@code @GetMapping})·바인딩({@code @PathVariable}, {@code @RequestAttribute})은 구현체에
 * 둔다(팀 관례, {@code CustomerPointApi} 참고). 이 경로는 {@code CustomerWebMvcConfig}의
 * {@code addPathPatterns}에 정확 경로로 등록해야 인증이 걸린다.
 */
@Tag(name = "customer-location", description = "고객 위치 폴링(부하테스트용)")
public interface CustomerLocationPollingApi {

    @Operation(operationId = "getCustomerDeliveryLocation", summary = "라이더 위치 폴링 조회(부하테스트용)",
            description = "호출 시점의 라이더 최신 위치 스냅샷을 반환한다. 위치가 없으면(미전송·TTL 만료) "
                    + "상태만 담아 200으로 응답한다 — 오류가 아니다. Redis 조회 자체가 실패하면 "
                    + "위치 없음으로 위장하지 않고 503을 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공(위치 없음 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 타인의 배송"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "추적 불가 상태(배차 전·완료·취소)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Redis 조회 실패")
    })
    ApiResponse<CustomerDeliveryLocationResponse> getDeliveryLocation(
            @Parameter(description = "배송요청 식별자", example = "1024") Long deliveryId,
            AuthenticatedCustomer customer);
}
