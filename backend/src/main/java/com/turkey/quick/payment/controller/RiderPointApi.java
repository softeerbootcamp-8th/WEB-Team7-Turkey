package com.turkey.quick.payment.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.payment.domain.PointTransactionType;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.dto.PointTransactionListResponse;
import com.turkey.quick.payment.dto.SettlementListResponse;
import com.turkey.quick.payment.dto.WithdrawalListResponse;
import com.turkey.quick.payment.dto.WithdrawalProcessRequest;
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

/**
 * 라이더 포인트 API 계약(문서 전용)
 *
 * <p>역할 분담 규칙·springdoc 스캔 조건·프록시 주의사항은 {@link CustomerPointApi} 와 동일하다 —
 * 이 인터페이스에는 순수 문서화 어노테이션만 두고, 매핑·바인딩·검증은 구현체에 둔다.
 * 인증 경로 등록은 {@code RiderWebMvcConfig}(라이더 인터셉터) 쪽에 해야 한다 — 고객 설정에 등록하면
 * 역할 검증이 어긋난다.
 *
 * <p>인증 주체는 {@link RiderSessionInterceptor} 가 request attribute 에 담아 둔
 * {@link AuthenticatedRider} 를 구현체에서 {@code @RequestAttribute} 로 받는다. riderId 를 요청으로
 * 받지 않는 이유는 고객 쪽과 같다 — 받으면 남의 지갑·정산을 지목할 수 있는 통로가 된다.
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
public interface RiderPointApi {

    @Operation(
            operationId = "getRiderPointBalance",
            summary = "포인트 잔액 조회",
            description = "로그인한 라이더의 출금 가능 포인트 잔액을 조회한다. 지갑은 회원 단위 개념이라 "
                    + "고객과 같은 스키마를 쓴다. 출금 요청 중(PENDING)인 금액은 이미 선차감되어 "
                    + "이 잔액에 포함되지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "미로그인 또는 세션 만료")
    })
    ApiResponse<PointBalanceResponse> getPointBalance(AuthenticatedRider rider);

    @Operation(
            operationId = "getRiderSettlements",
            summary = "정산 내역",
            description = "완료된 배송의 정산(rider_settlement)을 최신순으로 조회한다. "
                    + "배송별 수익 관점의 목록이며, 잔액 변화 관점은 거래 내역 API 를 쓴다. "
                    + "운행 기록 화면(/api/rider/history)의 주간 합계와 근거 테이블이 같다.")
    ApiResponse<SettlementListResponse> getSettlements(
            AuthenticatedRider rider,

            @Parameter(description = "페이지(0부터)")
            int page,

            @Parameter(description = "페이지 크기")
            int size);

    @Operation(
            operationId = "getRiderPointTransactions",
            summary = "포인트 거래 내역",
            description = "라이더 지갑의 원장을 최신순으로 조회한다. 라이더에게 나타나는 유형은 "
                    + "SETTLEMENT·WITHDRAWAL·WITHDRAWAL_REFUND 다. WITHDRAWAL·WITHDRAWAL_REFUND 행은 "
                    + "withdrawalStatus 로 그 출금이 대기 중(PENDING)인지 완료(COMPLETED)됐는지 구분할 "
                    + "수 있다(#90 후속) — 화면이 항상 같은 라벨로 보여 대기 중과 완료를 구분하지 "
                    + "못했던 문제를 이 필드로 고친다.")
    ApiResponse<PointTransactionListResponse> getPointTransactions(
            AuthenticatedRider rider,

            @Parameter(description = "거래 유형 필터(미지정 시 전체)")
            PointTransactionType type,

            @Parameter(description = "페이지(0부터)")
            int page,

            @Parameter(description = "페이지 크기")
            int size);

    @Operation(
            operationId = "requestRiderWithdrawal",
            summary = "출금 요청",
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
    ApiResponse<WithdrawalResponse> requestWithdrawal(
            AuthenticatedRider rider,
            WithdrawalRequest request);

    @Operation(
            operationId = "processRiderWithdrawal",
            summary = "출금 모의 처리",
            description = "모의 은행 이체(PayoutGateway)를 호출해 PENDING 인 출금을 COMPLETED 또는 "
                    + "FAILED 로 확정한다 — 결제 승인(PaymentGateway)과 같은 구조다. 성공·실패는 이체를 "
                    + "받는 쪽(모의 게이트웨이)이 판단하므로 요청은 결제창을 통과해 받아 온 것과 같은 "
                    + "불투명한 토큰만 보낸다. 실패 시 선차감했던 포인트를 같은 트랜잭션에서 복구하고 "
                    + "WITHDRAWAL_REFUND 원장을 남긴다. 이미 처리된 요청을 다시 처리하려 하면 409 로 "
                    + "거부한다(멱등 응답이 아니다 — 재처리는 오류다).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "처리 완료(COMPLETED 또는 FAILED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "존재하지 않는 출금 요청이거나 본인 것이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "이미 처리된 출금 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "이체 결과 불명(타임아웃) — PENDING 유지")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "authToken": "mock_decline"
                    }""")))
    ApiResponse<WithdrawalResponse> processWithdrawal(
            AuthenticatedRider rider,

            @Parameter(description = "처리할 출금 식별자")
            Long withdrawalId,

            WithdrawalProcessRequest request);

    @Operation(
            operationId = "getRiderWithdrawals",
            summary = "출금 내역",
            description = "출금 요청을 최신순으로 조회한다. 계좌는 요청 시점 스냅샷이라 "
                    + "이후 계좌를 변경해도 과거 내역의 표시는 바뀌지 않는다.")
    ApiResponse<WithdrawalListResponse> getWithdrawals(
            AuthenticatedRider rider,

            @Parameter(description = "페이지(0부터)")
            int page,

            @Parameter(description = "페이지 크기")
            int size);
}
