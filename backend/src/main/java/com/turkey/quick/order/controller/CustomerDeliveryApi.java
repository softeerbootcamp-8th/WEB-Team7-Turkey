package com.turkey.quick.order.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.ActiveDeliveryResponse;
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
import jakarta.validation.constraints.Min;
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

    @Operation(operationId = "createCustomerDelivery",
            summary = "배송요청 생성",
            description = "배송요청을 WAITING 으로 생성하고 예상 운임(ESTIMATE) 스냅샷을 남긴 뒤 "
                    + "그 요금만큼 포인트를 차감한다(REQ-ORD-002 + CUS-PAY-002). "
                    + "셋은 하나의 트랜잭션이라 어느 하나가 실패하면 전부 롤백된다 — 주문만 생기거나 "
                    + "포인트만 빠져나가는 상태는 만들어지지 않는다. "
                    + "요금은 좌표로 서버가 다시 계산하며, 요청의 estimatedFare 는 대조용이다. "
                    + "화면이 안내한 금액과 서버 계산이 다르면 주문을 만들지 않고 409 로 알린다 — "
                    + "사용자가 동의하지 않은 금액을 결제하지 않기 위해서다. "
                    + "고객은 진행 중(WAITING~DELIVERING) 요청을 1건만 가질 수 있다. "
                    + "같은 requestKey 로 재전송하면 새로 만들지 않고 기존 결과를 돌려주며 포인트도 "
                    + "다시 차감하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "생성 성공(재전송 시 기존 주문의 결과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "입력값 오류, 최대 배송 거리 초과, 또는 활성 요금 정책 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "402", description = "포인트 잔액이 배송요금보다 적음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "진행 중 배송요청이 이미 있거나 동일 요청이 동시에 처리 중, "
                            + "또는 서버 재계산 요금이 요청의 estimatedFare 와 다름")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "requestKey": "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34",
                      "estimatedFare": 6400,
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
            // 세션에서 얻은 고객이 곧 주문의 주인이다. 요청 바디로 받으면 남의 주문을 만들 수 있다.
            // 이 파라미터는 클라이언트가 채울 수 없으므로 스웨거 문서에도 노출되지 않는다.
            // (RiderDeliveryRequestApi 와 같은 방식 — 이 파일은 매핑을 인터페이스에 두는 옛 형태다)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer,

            @Valid @RequestBody DeliveryCreateRequest request);

    @Operation(summary = "배송요청 목록",
            description = "로그인한 고객의 이용기록을 요청 시각 최신순으로 조회한다. "
                    + "status 를 주면 해당 상태만 거른다. MVP 는 기간 필터를 제공하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공(결과 없으면 빈 목록)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "잘못된 페이지 정보(page<0 또는 size<1)")
    })
    @GetMapping
    ApiResponse<DeliveryListResponse> getDeliveries(
            @Parameter(description = "배송 상태 필터(미지정 시 전체)")
            @RequestParam(required = false) OrderStatus status,

            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") @Min(1) int size,

            @Parameter(hidden = true)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);

    @Operation(operationId = "getCustomerActiveDelivery",
            summary = "진행 중 배송 요약 조회",
            description = "로그인한 고객의 진행 중(WAITING~DELIVERING) 배송을 요약해 조회한다. "
                    + "고객당 진행 중 배송은 최대 1건이므로 결과는 0건 또는 1건이다. "
                    + "진행 중 배송이 없으면 응답 data 가 null 이다(빈 결과, 오류 아님). "
                    + "홈 화면 요약용이라 요금은 담지 않는다 — 상태·출발지·도착지·요청 시각만 준다. "
                    + "정적 경로라 /{deliveryId} 보다 먼저 매칭된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공(진행 중 배송이 없으면 data=null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료")
    })
    @GetMapping("/active")
    ApiResponse<ActiveDeliveryResponse> getActiveDelivery(
            @Parameter(hidden = true)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);

    @Operation(summary = "배송요청 상세",
            description = "주문 시점 스냅샷(주소·연락처·운임)과 상태 타임라인을 조회한다. "
                    + "추적 스냅샷과 달리 상태를 가리지 않는다 — 배차 전(WAITING)·완료·취소 주문도 조회된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청이 없거나 본인 것이 아님")
    })
    @GetMapping("/{deliveryId}")
    ApiResponse<DeliveryDetailResponse> getDelivery(
            @Parameter(description = "배송요청 식별자", example = "1234")
            @PathVariable Long deliveryId,

            @Parameter(hidden = true)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);

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

    @Operation(operationId = "cancelCustomerDelivery",
            summary = "배송요청 취소",
            description = "배차 전(WAITING)에만 허용한다. ASSIGNED 이상·완료는 MVP 범위 밖이라 거부된다. "
                    + "이미 취소된 주문에 다시 요청하면 그 결과를 그대로 돌려주고 중복 환급하지 않는다(멱등). "
                    + "취소와 포인트 환급(CUS-PAY-003)은 하나의 트랜잭션으로 처리된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "취소 성공(이미 취소된 경우 포함, 멱등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "배송요청이 없거나 본인 것이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "이미 배차되었거나 완료되어 취소할 수 없는 상태")
    })
    @PatchMapping("/{deliveryId}/cancel")
    ApiResponse<DeliveryCancelResponse> cancelDelivery(
            @Parameter(description = "배송요청 식별자", example = "1024")
            @PathVariable Long deliveryId,

            @Valid @RequestBody DeliveryCancelRequest request,

            @Parameter(hidden = true)
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);
}
