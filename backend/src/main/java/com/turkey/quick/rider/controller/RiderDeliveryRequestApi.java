package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestPageResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 라이더 콜(배차 대기 배송요청) API 문서 계약.
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
public interface RiderDeliveryRequestApi {

    @Operation(
            operationId = "getRiderDeliveryRequests",
            summary = "배차 대기 콜 목록",
            description = "AVAILABLE 인 라이더에게 수락 가능한 WAITING 요청을 반환한다. "
                    + "라이더 좌표(latitude/longitude)를 요청 파라미터로 받아 그 반경 내 주문만 "
                    + "bounding box 인덱스로 거른다(#367). 좌표를 안 보내면 위치 필터 없이 전체를 "
                    + "반환하고, 거리 필드는 null, DISTANCE 정렬은 REQUESTED_AT 으로 대체한다 "
                    + "(#55 계약 확정 — 위치 없음은 에러가 아니다). "
                    + "운임·배송거리 범위 필터와 물품 종류 필터, keyset(커서) 페이지네이션이 "
                    + "추가됐다(#60/#522). 정렬 기준에 DELIVERY_DISTANCE(픽업→도착지 배송거리, "
                    + "좌표 무관하게 항상 계산 가능)가 추가됐고, 정렬 방향은 기준별 고정값(FARE만 "
                    + "내림차순, 나머지는 오름차순)이라 요청 파라미터로 따로 안 받는다(#522 — "
                    + "실제로 쓰는 조합이 고정 방향 하나뿐으로 확정돼 파라미터를 없앴다). "
                    + "커서는 이전 페이지 마지막 항목의 정렬값(sort에 해당하는 after* 필드 하나)과 "
                    + "afterId를 그대로 돌려보내면 된다 — 첫 페이지는 전부 생략한다. "
                    + "(#55) 라이더 식별을 위한 인증 파라미터가 이 계약에 빠져 있었어 추가함 — "
                    + "다른 세 메서드(상세/수락/넘기기)는 각자 이슈에서 채운다.")
    ApiResponse<RiderDeliveryRequestPageResponse> getDeliveryRequests(
            @Parameter(hidden = true)
            AuthenticatedRider rider,

            @Parameter(description = "라이더 현재 위도(선택, 없으면 위치 무시하고 전체 반환)", example = "37.5006")
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,

            @Parameter(description = "라이더 현재 경도(선택, 없으면 위치 무시하고 전체 반환)", example = "127.0366")
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,

            @Parameter(description = "검색 반경(m)", example = "3000")
            int radiusMeters,

            @Parameter(description = "정렬 기준. 방향은 기준별로 고정(FARE=내림차순, 나머지=오름차순)이라 "
                    + "별도 파라미터로 받지 않는다", example = "DISTANCE",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            allowableValues = {"DISTANCE", "FARE", "REQUESTED_AT", "DELIVERY_DISTANCE"}))
            String sort,

            @Parameter(description = "예상 정산액(운임) 최소값, 원(선택)", example = "3000")
            Long fareMin,

            @Parameter(description = "예상 정산액(운임) 최대값, 원(선택)", example = "10000")
            Long fareMax,

            @Parameter(description = "배송거리(픽업→도착지) 최소값, m(선택)", example = "500")
            Integer distanceMin,

            @Parameter(description = "배송거리(픽업→도착지) 최대값, m(선택)", example = "5000")
            Integer distanceMax,

            @Parameter(description = "물품 종류 필터(선택, 생략하면 전체)")
            ItemType itemType,

            @Parameter(description = "페이지 크기(1~100)", example = "20")
            int size,

            @Parameter(description = "커서: 이전 페이지 마지막 항목의 라이더→픽업지 거리(m). "
                    + "sort=DISTANCE일 때만 채운다", example = "740")
            Integer afterDistanceMeters,

            @Parameter(description = "커서: 이전 페이지 마지막 항목의 예상 정산액. sort=FARE일 때만 채운다",
                    example = "6400")
            Long afterFare,

            @Parameter(description = "커서: 이전 페이지 마지막 항목의 요청 시각. sort=REQUESTED_AT일 때만 채운다",
                    example = "2026-07-28T02:10:00")
            LocalDateTime afterRequestedAt,

            @Parameter(description = "커서: 이전 페이지 마지막 항목의 배송거리(픽업→도착지, m). "
                    + "sort=DELIVERY_DISTANCE일 때만 채운다", example = "3200")
            Integer afterDeliveryDistanceMeters,

            @Parameter(description = "커서: 이전 페이지 마지막 항목의 배송요청 식별자(정렬값이 같을 때 "
                    + "tiebreaker). 첫 페이지면 생략", example = "1024")
            Long afterId);

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
    ApiResponse<RiderDeliveryRequestDetailResponse> getDeliveryRequest(
            @Parameter(hidden = true)
            AuthenticatedRider rider,

            @Parameter(description = "배송요청 식별자", example = "1024")
            Long deliveryId);

    @Operation(summary = "배차 확정(콜 수락)",
            description = "배송 WAITING→ASSIGNED + 라이더 AVAILABLE→BUSY 를 한 트랜잭션으로 처리한다(ADR-006 "
                    + "조건부 UPDATE, 주문→라이더 순서 고정). 요청당 라이더 1명, 라이더당 진행 배송 1건이며 "
                    + "경쟁 실패는 409 로 끝난다. (#56) 라이더 식별을 위한 인증 파라미터를 이 메서드에도 추가함.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "배차 확정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "존재하지 않는 배송요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "다른 라이더가 먼저 수락했거나, 취소됐거나, 라이더가 AVAILABLE 이 아니거나 "
                            + "이미 다른 배송을 수행 중임")
    })
    ApiResponse<RiderDeliveryRequestAcceptResponse> acceptDeliveryRequest(
            @Parameter(hidden = true)
            AuthenticatedRider rider,

            @Parameter(description = "배송요청 식별자", example = "1024")
            Long deliveryId);

    @Operation(summary = "콜 넘기기",
            description = "해당 라이더의 목록에서만 감춘다. 주문 상태는 WAITING 그대로이고 다른 라이더에게는 계속 보인다.")
    ApiResponse<Void> skipDeliveryRequest(
            @Parameter(description = "배송요청 식별자", example = "1024")
            Long deliveryId);
}
