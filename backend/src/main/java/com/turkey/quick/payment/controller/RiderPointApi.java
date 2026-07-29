package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.dto.SettlementListResponse;
import com.turkey.quick.payment.dto.WithdrawalListResponse;
import com.turkey.quick.payment.dto.WithdrawalRequest;
import com.turkey.quick.payment.dto.WithdrawalResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 라이더 포인트 API 계약
 *
 * <p>구현체 규칙·springdoc 스캔 조건은 {@link CustomerPointApi} 와 동일하다.
 * 인증 경로 등록은 {@code RiderWebMvcConfig}(라이더 인터셉터) 쪽에 해야 한다 — 고객 설정에 등록하면
 * 역할 검증이 어긋난다.
 *
 * <p>인증 주체는 {@link RiderSessionInterceptor} 가 request attribute 에 담아 둔
 * {@link AuthenticatedRider} 를 {@code @RequestAttribute} 로 받는다. riderId 를 요청으로 받지 않는
 * 이유는 고객 쪽과 같다 — 받으면 남의 지갑·정산을 지목할 수 있는 통로가 된다.
 *
 * <p><b>출금 API 를 payment 에 둔 이유</b>: rider_withdrawal 엔터티는 rider 패키지에 있지만,
 * 출금이 실제로 하는 일은 지갑 잔액 선차감 + WITHDRAWAL 원장 기록이다. 원장을 쓰는 코드가
 * 원장 소유 패키지 밖으로 나가면 잔액 갱신 규칙(조건부 UPDATE + 원장 기록)이 두 패키지에 흩어진다.
 * 그래서 엔터티는 rider 에 두고 API·서비스는 payment 에 둔다.
 * (RiderDeliveryHistoryApi 주석에 미결로 남아 있던 항목 — 이 계약으로 payment 로 정한다.)
 *
 * <p><b>여기 없는 것</b>: 정산 생성(SETTLEMENT 적립)은 엔드포인트가 아니다. 배송 완료 트랜잭션
 * (배송 DELIVERING→COMPLETED + 라이더 BUSY→AVAILABLE + 정산 생성)에서 함께 일어나므로
 * 외부에서 호출할 수 있게 열면 완료 없이 정산만 생기는 경로가 만들어진다. 여기서는 조회만 한다.
 */
@Tag(name = "rider-point", description = "라이더 포인트 — 잔액, 정산 내역, 거래 내역, 출금")
@RequestMapping("/api/rider/points")
public interface RiderPointApi {

    @Operation(summary = "포인트 잔액 조회",
            description = "로그인한 라이더의 출금 가능 포인트 잔액을 조회한다. 지갑은 회원 단위 개념이라 "
                    + "고객과 같은 스키마를 쓴다. 출금 요청 중(PENDING)인 금액은 이미 선차감되어 "
                    + "이 잔액에 포함되지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료")
    })
    @GetMapping
    ApiResponse<PointBalanceResponse> getPointBalance(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider);

    @Operation(summary = "정산 내역",
            description = "완료된 배송의 정산(rider_settlement)을 최신순으로 조회한다. "
                    + "배송별 수익 관점의 목록이며, 잔액 변화 관점은 거래 내역 API 를 쓴다. "
                    + "운행 기록 화면(/api/rider/history)의 주간 합계와 근거 테이블이 같다.")
    @GetMapping("/settlements")
    ApiResponse<SettlementListResponse> getSettlements(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "포인트 거래 내역",
            description = "라이더 지갑의 원장을 최신순으로 조회한다. 라이더에게 나타나는 유형은 "
                    + "SETTLEMENT·WITHDRAWAL·WITHDRAWAL_REFUND 다.")
    @GetMapping("/transactions")
    ApiResponse<PointTransactionListResponse> getPointTransactions(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Parameter(description = "거래 유형 필터(미지정 시 전체)")
            @RequestParam(required = false) PointTransactionType type,

            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "출금 요청",
            description = "등록된 정산 계좌로 출금을 요청한다. 계좌는 요청 바디로 받지 않고 "
                    + "rider_payout_account 의 값을 스냅샷으로 복사한다. 요청 즉시 잔액을 선차감하고 "
                    + "WITHDRAWAL 원장을 남기며, 송금 실패 시 WITHDRAWAL_REFUND 로 복구한다. "
                    + "같은 requestKey 로 재전송하면 새로 만들지 않고 기존 결과를 돌려준다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "출금 요청 생성(PENDING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "잔액 부족 또는 정산 계좌 미등록")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "requestKey": "8f3d2b71-0c4e-4a19-9d55-1e7a3c6b40aa",
                      "amount": 50000
                    }""")))
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<WithdrawalResponse> requestWithdrawal(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Valid @RequestBody WithdrawalRequest request);

    @Operation(summary = "출금 내역",
            description = "출금 요청을 최신순으로 조회한다. 계좌는 요청 시점 스냅샷이라 "
                    + "이후 계좌를 변경해도 과거 내역의 표시는 바뀌지 않는다.")
    @GetMapping("/withdrawals")
    ApiResponse<WithdrawalListResponse> getWithdrawals(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,

            @Parameter(description = "페이지(0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20") int size);
}
