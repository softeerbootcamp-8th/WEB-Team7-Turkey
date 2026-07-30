package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 라이더 콜(배차 대기 배송요청) API 계약(인터페이스 전용, 구현 없음).
 *
 * <p>수락 전에는 고객 연락처와 상세 주소를 내려주지 않는다. 배차가 확정된 뒤에만
 * {@link RiderDeliveryApi} 응답에서 열린다.
 *
 * <p><b>accept 는 이 서비스의 핵심 동시성 지점이다.</b> 배송 WAITING→ASSIGNED,
 * 라이더 AVAILABLE→BUSY, 배차 관계 생성이 하나의 트랜잭션이며 부분 성공이 없다.
 * 경쟁에서 진 요청은 409 로 명확히 실패한다(빈 성공 응답을 주지 않는다).
 * 조건부 UPDATE 로 판정할지 락을 쓸지는 구현 시 ADR 결정을 따른다.
 *
 * <p>배차 포기(수락 후 취소)는 MVP 범위 밖이라 이 계약에 없다. skip 은 아직 수락하지 않은
 * 콜을 목록에서 내리는 것뿐이라 상태를 바꾸지 않는다.
 */
@Tag(name = "rider-request", description = "라이더 콜 — 배차 대기 요청 조회·수락·넘기기")
@RequestMapping("/api/rider/requests")
public interface RiderDeliveryRequestApi {

    @Operation(summary = "배차 대기 콜 목록",
            description = "AVAILABLE 인 라이더에게 수락 가능한 WAITING 요청을 반환한다. "
                    + "라이더 최신 위치(Redis GEO) 기준 반경으로 거르며, 위치가 없으면 거리 필드는 null 이다. "
                    + "(#55) 라이더 식별을 위한 인증 파라미터가 이 계약에 빠져 있었어 추가함 — "
                    + "다른 세 메서드(상세/수락/넘기기)는 각자 이슈에서 채운다.")
    @GetMapping
    ApiResponse<List<RiderDeliveryRequestSummaryResponse>> getDeliveryRequests(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Parameter(description = "검색 반경(m)", example = "3000")
            @RequestParam(defaultValue = "3000") int radiusMeters,

            @Parameter(description = "정렬 기준", example = "DISTANCE",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            allowableValues = {"DISTANCE", "FARE", "REQUESTED_AT"}))
            @RequestParam(defaultValue = "DISTANCE") String sort);

    @Operation(summary = "배차 대기 콜 상세",
            description = "수락 판단에 필요한 거리·운임·소요시간·좌표를 조회한다. "
                    + "존재하지 않거나 이미 다른 라이더가 가져갔거나 취소된 주문이면 404 다. "
                    + "(#57) 라이더 식별을 위한 인증 파라미터를 이 메서드에도 추가함(#55와 같은 이유).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "상세 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "존재하지 않거나 더 이상 WAITING 이 아닌 주문")
    })
    @GetMapping("/{deliveryId}")
    ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId);

    @Operation(summary = "배차 확정(콜 수락)",
            description = "배송 WAITING→ASSIGNED + 라이더 AVAILABLE→BUSY 를 한 트랜잭션으로 처리한다. "
                    + "요청당 라이더 1명, 라이더당 진행 배송 1건이며 경쟁 실패는 409 로 끝난다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "배차 확정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "다른 라이더가 먼저 수락했거나, 라이더가 AVAILABLE 이 아님")
    })
    @PostMapping("/{deliveryId}/accept")
    ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId);

    @Operation(summary = "콜 넘기기",
            description = "해당 라이더의 목록에서만 감춘다. 주문 상태는 WAITING 그대로이고 다른 라이더에게는 계속 보인다.")
    @PostMapping("/{deliveryId}/skip")
    ApiResponse<Void> skipDeliveryRequest(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId);
}
