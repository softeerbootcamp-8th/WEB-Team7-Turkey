package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointChargeConfirmRequest;
import com.turkey.quick.payment.dto.PointChargeConfirmResponse;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 고객 포인트 API 계약(문서 전용)
 *
 * <p><b>역할 분담</b>: 이 인터페이스에는 순수 문서화 어노테이션({@code @Tag}, {@code @Operation},
 * {@code @ApiResponses}, {@code @Parameter}, swagger {@code @RequestBody})만 둔다. 경로·HTTP 메서드
 * 매핑({@code @RequestMapping}, {@code @GetMapping} 등)과 바인딩·검증
 * ({@code @RequestAttribute}, {@code @RequestParam}, {@code @PathVariable}, {@code @Valid})은
 * 전부 구현체에 둔다.
 *
 * <p>이렇게 나누는 이유는 두 가지다. 첫째, 인터페이스에 Bean Validation 제약이 없으므로
 * 오버라이드에서 제약을 재선언해 HV000151 로 실패할 여지가 애초에 없다. 둘째, 실제 동작에 영향을
 * 주는 어노테이션은 로직과 같은 파일에서 보이고, 수십 줄짜리 명세는 여기로 분리된다.
 * springdoc 은 상위 타입의 어노테이션을 함께 읽으므로 문서는 정상 생성된다.
 *
 * <p><b>주의</b>: 매핑 어노테이션이 구현체에만 있으므로 컨트롤러가 JDK 동적 프록시로 감싸이면
 * 매핑이 유실되어 404 가 난다. Spring Boot 는 CGLIB 프록시가 기본이라 보통 문제없지만,
 * {@code spring.aop.proxy-target-class=false} 를 쓰는 경우 확인이 필요하다.
 *
 * <p><b>이 인터페이스만으로는 /v3/api-docs 에 아무것도 나오지 않는다.</b> springdoc 은 빈으로
 * 등록된 컨트롤러를 스캔하므로, 구현체가 생겨야 문서와 Orval 훅이 만들어진다.
 *
 * <p>인증은 쿠키 세션이며 세션에서 얻은 고객이 곧 지갑의 주인이다. 따라서 어느 API 도
 * customerId 를 요청 파라미터·바디로 받지 않는다 — 받으면 남의 지갑을 지목할 수 있는 통로가 된다.
 * 대신 {@link CustomerSessionInterceptor} 가 쿠키 → 세션 → 회원 재조회 → 역할·상태 확인을 마치고
 * request attribute 에 담아 둔 {@link AuthenticatedCustomer} 를 구현체에서 {@code @RequestAttribute}
 * 로 받는다({@code CustomerSessionController} 와 같은 관례). 이 파라미터는 클라이언트가 채울 수
 * 없으므로 스웨거 문서에도 노출되지 않는다.
 *
 * <p>또한 이 경로들은 {@code CustomerWebMvcConfig} 의 {@code addPathPatterns} 에 반드시 등록해야
 * 인증이 걸린다(팀 정책상 Spring Security 미사용이라 선언적 자동 적용이 없다).
 *
 * <p>패키지 근거: 지갑·원장은 payment 도메인 소관이다(RiderDeliveryHistoryApi 주석 참조).
 * 경로는 팀 규칙(액터 우선)에 따라 {@code /api/customer/points} 를 쓴다 — 패키지는 도메인 기준,
 * URL 은 액터 기준으로 서로 다른 축을 따른다.
 *
 * <p><b>여기 없는 것</b>: 배송요청 결제(ORDER_USE 차감)는 이 계약에 없다. 포인트 차감은
 * 주문 생성/확정 트랜잭션 안에서 일어나야 하므로 별도 엔드포인트로 노출하면 "차감했는데 주문 실패"
 * 같은 부분 성공이 생긴다. 취소 환불(ORDER_REFUND)도 같은 이유로 order 도메인 소관이다.
 */
@Tag(name = "customer-point", description = "고객 포인트 — 잔액, 충전, 거래 내역")
public interface CustomerPointApi {

    @Operation(
            operationId = "getCustomerPointBalance",
            summary = "포인트 잔액 조회",
            description = "로그인한 고객의 사용 가능 포인트 잔액을 조회한다(CUS-POINT-001). "
                    + "잔액의 정본은 서버의 point_wallet 이며 클라이언트가 전달한 잔액은 신뢰하지 않는다. "
                    + "지갑은 회원 가입 시 잔액 0 으로 생성되므로 정상 고객에게 지갑 없음은 발생하지 않는다 — "
                    + "없다면 데이터 정합성 오류로 보고 500 으로 다룬다."
                    + "멤버키는 세션에서 가져오고 있다. 따라서 인가를 포함하고 있다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료")
    })
    ApiResponse<PointBalanceResponse> getPointBalance(AuthenticatedCustomer customer);

    @Operation(
            operationId = "getCustomerPointTransactions",
            summary = "포인트 거래 내역",
            description = "잔액 변화를 남긴 원장(point_transaction)을 최신순으로 조회한다. "
                    + "type 을 주면 해당 거래 유형만 거른다. 화면 상단 카드용 현재 잔액을 같은 응답에 담는다.")
    ApiResponse<PointTransactionListResponse> getPointTransactions(
            AuthenticatedCustomer customer,

            @Parameter(description = "거래 유형 필터(미지정 시 전체)")
            PointTransactionType type,

            @Parameter(description = "페이지(0부터)")
            int page,

            @Parameter(description = "페이지 크기")
            int size);

    @Operation(
            operationId = "requestCustomerPointCharge",
            summary = "포인트 충전 요청",
            description = "충전 결제를 PENDING 으로 생성한다. 이 시점에는 잔액이 변하지 않는다. "
                    + "같은 chargeRequestKey 로 재전송하면 새로 만들지 않고 기존 결과를 돌려준다. "
                    + "MVP 는 실 PG 연동이 아닌 모킹 흐름이지만 요청·승인 2단계 구조는 유지한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "충전 요청 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "충전 금액이 양수가 아님")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "chargeRequestKey": "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34",
                      "amount": 10000,
                      "paymentMethod": "CARD"
                    }""")))
    ApiResponse<PointChargeResponse> requestPointCharge(
            AuthenticatedCustomer customer,
            PointChargeRequest request);

    @Operation(
            operationId = "confirmCustomerPointCharge",
            summary = "포인트 충전 모의 승인",
            description = "PG 승인을 받아 PENDING 충전을 PAID 로 전이한다(CUS-POINT-003). "
                    + "상태 전이·잔액 증가·CHARGE 원장 기록은 한 트랜잭션이라 셋 중 하나라도 실패하면 "
                    + "전부 롤백된다. 단 PG 호출은 그 트랜잭션 밖에서 일어난다 — 외부 응답을 기다리는 "
                    + "동안 DB 커넥션과 행 잠금을 쥐지 않기 위해서다. "
                    + "승인 금액은 항상 요청 금액 전액이다(부분 결제 없음). "
                    + "요청의 amount 는 대조용이며 승인 금액으로 쓰이지 않는다 — 서버가 기억하는 요청 "
                    + "금액과 다르면 400 이다. "
                    + "승인 여부는 PaymentGateway 가 결정한다(MVP 는 MockPaymentGateway). PG 가 거절하면 "
                    + "충전하지 않고 FAILED 로 확정하며, 이는 오류가 아니라 200 응답(status=FAILED)으로 "
                    + "돌려준다 — 예외로 처리하면 트랜잭션이 롤백되어 FAILED 전이 자체가 사라진다. "
                    + "PG 응답을 받지 못한 경우(TIMEOUT)는 결제 성사 여부를 알 수 없어 PENDING 을 유지하고 "
                    + "502 로 알린다. "
                    + "이미 PAID 인 충전에 다시 승인 요청이 오면 잔액을 또 늘리지 않고 그때의 결과를 "
                    + "200 으로 돌려준다(멱등). 이때 balanceAfter 는 지갑의 현재 잔액이 아니라 승인 시점 "
                    + "원장의 balance_after 다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "승인 성공 · 모의 결제 실패(FAILED) · 이미 승인된 건의 멱등 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "요청 금액과 결제 금액 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "본인의 충전 건이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "존재하지 않는 충전 건"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "승인할 수 없는 상태이거나, 앞선 승인이 반영 도중 끊겨 결제 확인이 "
                            + "필요한 건(PENDING 이면서 승인 식별자가 이미 기록된 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "PG 응답 불명 — 충전 건은 PENDING 으로 남는다")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "amount": 10000,
                      "authToken": "mock_auth_9f2c41"
                    }""")))
    ApiResponse<PointChargeConfirmResponse> confirmPointCharge(
            AuthenticatedCustomer customer,

            @Parameter(description = "충전 식별자", example = "331")
            Long pointChargeId,

            PointChargeConfirmRequest request);
}
