package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.DeliveryCancelRequest;
import com.turkey.quick.order.dto.DeliveryCancelResponse;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.DeliveryCreateResponse;
import com.turkey.quick.order.dto.DeliveryDetailResponse;
import com.turkey.quick.order.dto.DeliveryListResponse;
import com.turkey.quick.order.dto.DeliveryTrackingResponse;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.dto.FareQuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 고객 배송요청 API 계약
 *
 * <p>경로·HTTP 메서드·스키마를 여기에 고정하고, 구현체는 {@code @RestController} 만 붙여
 * 이 인터페이스를 구현한다. 매핑과 검증 어노테이션을 구현체에서 다시 선언하지 않는다
 * (Bean Validation 은 오버라이드에서 제약을 재선언하면 HV000151 로 실패한다).
 *
 * <p><b>이 인터페이스만으로는 /v3/api-docs 에 아무것도 나오지 않는다.</b> springdoc 은 빈으로
 * 등록된 컨트롤러를 스캔하므로, 구현체가 생겨야 문서와 Orval 훅이 만들어진다.
 *
 * <p>인증은 쿠키 세션이며 세션에서 얻은 고객이 곧 조회·변경 주체다. 따라서 어느 API 도
 * customerId 를 파라미터로 받지 않는다 — 받으면 남의 주문을 지목할 수 있는 통로가 된다.
 *
 * <p>경로 근거: 팀 규칙(액터 우선 경로)에 따라 {@code /api/customer/...} 를 쓰고, 동적 세그먼트는
 * 프론트 라우트(`customer/deliveries/$deliveryId`)에 맞춰 {@code {deliveryId}} 로 통일한다.
 * 초안 문서의 {@code /api/orders} 와는 다르다.
 */
@Tag(name = "customer-delivery", description = "고객 배송요청 — 견적, 생성, 조회, 추적, 취소")
@RequestMapping("/api/customer/deliveries")
public interface CustomerDeliveryApi {

    @Operation(summary = "요금 견적",
            description = "주문을 만들지 않고 금액만 계산한다. 산정 기준 거리는 좌표로 서버가 구한다.")
    @PostMapping("/quote")
    ApiResponse<FareQuoteResponse> quoteFare(@RequestBody FareQuoteRequest request);

    @Operation(summary = "배송요청 생성",
            description = "배송요청을 WAITING 으로 생성하고 예상 운임(ESTIMATE) 스냅샷을 함께 남긴다. "
                    + "고객은 진행 중(WAITING~DELIVERING) 요청을 1건만 가질 수 있다. "
                    + "같은 requestKey 로 재전송하면 새로 만들지 않고 기존 결과를 돌려준다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "진행 중 배송요청이 이미 있음")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<DeliveryCreateResponse> createDelivery(
            @Valid @RequestBody DeliveryCreateRequest request);

    @Operation(summary = "배송요청 목록",
            description = "로그인한 고객의 이용기록을 최신순으로 조회한다. status 를 주면 해당 상태만 거른다.")
    @GetMapping
    ApiResponse<DeliveryListResponse> getDeliveries(
            @Parameter(description = "배송 상태 필터(미지정 시 전체)")
            @RequestParam(required = false) OrderStatus status,

            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "배송요청 상세", description = "주문 시점 스냅샷(주소·연락처·운임)과 상태 타임라인을 조회한다.")
    @GetMapping("/{deliveryId}")
    ApiResponse<DeliveryDetailResponse> getDelivery(
            @Parameter(description = "배송요청 식별자", example = "1234")
            @PathVariable Long deliveryId);

    @Operation(summary = "배송 추적 스냅샷",
            description = "추적 화면 진입 시 한 번 그릴 상태·타임라인·라이더 정보를 조회한다. "
                    + "이후 위치·상태 갱신은 location 도메인의 SSE 스트림이 밀어 준다(폴링하지 않는다(변동가능)).")
    @GetMapping("/{deliveryId}/tracking")
    ApiResponse<DeliveryTrackingResponse> getDeliveryTracking(
            @Parameter(description = "배송요청 식별자", example = "1234")
            @PathVariable Long deliveryId);

    @Operation(summary = "배송요청 취소",
            description = "배차 전(WAITING)에만 허용한다. ASSIGNED 이상은 MVP 범위 밖이라 거부된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "취소 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "이미 배차되었거나 취소할 수 없는 상태")
    })
    @PatchMapping("/{deliveryId}/cancel")
    ApiResponse<DeliveryCancelResponse> cancelDelivery(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId,

            @Valid @RequestBody DeliveryCancelRequest request);
}
