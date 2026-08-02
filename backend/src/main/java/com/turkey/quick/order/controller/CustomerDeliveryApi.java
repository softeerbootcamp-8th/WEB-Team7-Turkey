package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "itemType": "DOCUMENT",
                      "pickupAddress": {
                        "roadAddress": "서울 강남구 테헤란로 152",
                        "detailAddress": "5층 프론트데스크",
                        "postalCode": "06236",
                        "latitude": 37.5006,
                        "longitude": 127.0366
                      },
                      "destinationAddress": {
                        "roadAddress": "서울 송파구 올림픽로 300",
                        "detailAddress": "1동 관리사무소",
                        "postalCode": "05551",
                        "latitude": 37.5145,
                        "longitude": 127.1059
                      }
                    }""")))
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "requestKey": "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34",
                      "itemType": "DOCUMENT",
                      "pickup": {
                        "roadAddress": "서울 강남구 테헤란로 152",
                        "detailAddress": "5층 프론트데스크",
                        "postalCode": "06236",
                        "latitude": 37.5006,
                        "longitude": 127.0366
                      },
                      "destination": {
                        "roadAddress": "서울 송파구 올림픽로 300",
                        "detailAddress": "1동 관리사무소",
                        "postalCode": "05551",
                        "latitude": 37.5145,
                        "longitude": 127.1059
                      },
                      "sender": {
                        "name": "김고객",
                        "phoneNumber": "010-1234-5678"
                      },
                      "recipient": {
                        "name": "이수령",
                        "phoneNumber": "010-9876-5432"
                      }
                    }""")))
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

    /**
     * 실패 응답이 SSE 스트림({@code GET .../tracking/stream})과 정확히 같은 판정을 쓴다 — 즉
     * <b>이 API 가 200 이면 그 스트림도 열린다.</b> 프론트가 이 보장에 기대는 이유는 브라우저
     * {@code EventSource} 가 상태코드·본문을 스크립트에 노출하지 않아, 스트림이 왜 실패했는지 알 수
     * 있는 통로가 이 REST 뿐이라는 것이다({@code docs/04-frontend-api-map.md} §7).
     *
     * <p>{@code estimatedArrivalAt} 은 <b>현재 항상 null</b> 이다(산정 근거가 없다).
     * {@code steps} 는 {@code delivery_order} 의 단계별 시각 컬럼에서 파생한다.
     */
    @Operation(summary = "배송 추적 스냅샷",
            description = "추적 화면 진입 시 한 번 그릴 상태·타임라인·라이더 정보를 조회한다. "
                    + "이후 위치·상태 갱신은 location 도메인의 SSE 스트림이 밀어 준다(폴링하지 않는다(변동가능)). "
                    + "실패 판정은 스트림과 동일하다: 404(없거나 타인 주문), 409(WAITING·COMPLETED·CANCELED).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청이 없거나 본인 것이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "추적할 수 없는 상태(배차 전·완료·취소)")
    })
    @GetMapping("/{deliveryId}/tracking")
    ApiResponse<DeliveryTrackingResponse> getDeliveryTracking(
            @Parameter(description = "배송요청 식별자", example = "1234")
            @PathVariable Long deliveryId,

            @Parameter(hidden = true)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);

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
