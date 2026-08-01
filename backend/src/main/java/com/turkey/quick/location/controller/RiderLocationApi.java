package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.dto.RiderLocationUpdateResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 라이더 위치 갱신 API 계약.
 *
 * <p>경로·HTTP 메서드·스키마를 여기에 고정하고, 구현체는 {@code @RestController} 만 붙여 이
 * 인터페이스를 구현한다. 매핑과 검증 어노테이션을 구현체에서 다시 선언하지 않는다 — Bean
 * Validation 은 오버라이드에서 제약을 재선언하면 HV000151 로 실패한다
 * ({@code CustomerDeliveryApi} 와 같은 규칙).
 *
 * <p><b>경로 근거</b>: {@code /api/rider/session} · {@code /api/rider/operating-status} 와 같은
 * "인증된 라이더의 단일 하위 자원" 형태다. 이 저장소에서 컬렉션은 복수형
 * ({@code /deliveries}, {@code /requests}, {@code /login-ids})이므로, 목록으로 조회할 대상이
 * 아닌 "현재 위치"는 단수형이 맞다.
 *
 * <p><b>PUT 이 아니라 POST 인 이유</b>: 서버가 정책상 갱신을 거부하거나 값을 버릴 수 있고(#82
 * 필터), 부수 효과(SSE 전파 #78, 이력 선별 저장 #102)가 따른다. 멱등한 전체 교체가 아니다.
 *
 * <p>라이더 식별자는 경로·본문 어디에도 받지 않는다 — 세션에서 얻는다. 받으면 남의 위치를
 * 지목할 수 있는 통로가 된다.
 */
@Tag(name = "rider-location", description = "라이더 현재 위치 갱신")
@RequestMapping("/api/rider/location")
public interface RiderLocationApi {

    @Operation(
            operationId = "updateRiderLocation",
            summary = "라이더 현재 위치 갱신",
            description = """
                    운행 중(AVAILABLE·BUSY)인 라이더의 최신 위치를 갱신한다.

                    요청이 정상이어도 값을 쓸 수 없으면 200 으로 응답하면서 버린다(응답 reason 참고).
                    정확도가 기준을 넘거나(LOW_ACCURACY) 측정 시각이 너무 오래됐거나(STALE)
                    이전 위치보다 과거·동일한 시각(NON_MONOTONIC)인 경우다. 실내·지하 측위나 탭 복귀
                    직후처럼 정상 운행 중에도 발생하는 조건이라 오류로 응답하지 않는다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "갱신 성공 또는 정책에 따른 폐기(응답 reason 으로 구분)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "좌표 범위 밖, 필수 값 누락, 정확도 음수, 측정 시각이 미래"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "세션 없음·만료, 역할 불일치, 비활성 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "운행 중(AVAILABLE·BUSY)이 아님")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "latitude": 37.4979,
                      "longitude": 127.0276,
                      "measuredAt": "2026-07-29T12:34:56.789Z",
                      "accuracyMeters": 12.5
                    }""")))
    @PostMapping
    ApiResponse<RiderLocationUpdateResponse> updateRiderLocation(
            @Valid @RequestBody RiderLocationUpdateRequest request,

            // 세션 인터셉터가 request attribute 로 넣어 준 값이다. 클라이언트가 보내는 입력이
            // 아니므로 문서에서 감춘다 — 감추지 않으면 springdoc 이 파라미터로 노출하고,
            // Orval 이 그것을 인자로 받는 훅을 만들어 프론트가 라이더 식별자를 넘길 수 있는
            // 것처럼 보이게 된다.
            @Parameter(hidden = true)
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider);
}
